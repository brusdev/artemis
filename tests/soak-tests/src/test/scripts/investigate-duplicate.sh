#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Investigate a ScaleDownSoakTest duplicate after the test JVM stops (e.g. halt).
#
# Dup identity is _AMQ_DUPL_ID (logged as id=), e.g. prd-0-srv-1-msg-42.
# Primary output is MESSAGE_PATH.txt: reconstructs broker copies + consumer hops
# to show where/when the logical message was duplicated.
#
# Usage:
#   ./src/test/scripts/investigate-duplicate.sh \
#       --id 'prd-0-srv-1-msg-42' \
#       [--first-consumer-id N] [--first-server-id N] \
#       [--consumer-id N] [--server-id N] \
#       [--first-jms-id 'ID:...'] [--jms-id 'ID:...'] \
#       [--delivery-count N] [--first-delivery-count N] \
#       [--keep-running]
#
# After halt(-1): brokers usually stay up. This script stops them (unless --keep-running),
# then runs data print / data exp, extracts full journal records, builds MESSAGE_PATH.txt
# (copy lifecycle + duplication hypothesis), and TIMELINE.txt (chronological detail).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# .../tests/soak-tests/src/test/scripts -> soak-tests module root
SOAK_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BROKERS_ROOT="${SOAK_ROOT}/target/scaledown"
OUT_DIR="${SOAK_ROOT}/target/duplicate-investigate-$(date +%Y%m%d-%H%M%S)"
KEEP_RUNNING=0
DUP_ID=""
FIRST_JMS_ID=""
JMS_ID=""
DELIVERY_COUNT=""
FIRST_DELIVERY_COUNT=""
FIRST_CONSUMER_ID=""
FIRST_SERVER_ID=""
CONSUMER_ID=""
SERVER_ID=""

usage() {
  sed -n '19,36p' "$0" | sed 's/^# \?//'
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --id) DUP_ID="$2"; shift 2 ;;
    --first-jms-id) FIRST_JMS_ID="$2"; shift 2 ;;
    --jms-id) JMS_ID="$2"; shift 2 ;;
    --delivery-count) DELIVERY_COUNT="$2"; shift 2 ;;
    --first-delivery-count) FIRST_DELIVERY_COUNT="$2"; shift 2 ;;
    --first-consumer-id) FIRST_CONSUMER_ID="$2"; shift 2 ;;
    --first-server-id) FIRST_SERVER_ID="$2"; shift 2 ;;
    --consumer-id) CONSUMER_ID="$2"; shift 2 ;;
    --server-id) SERVER_ID="$2"; shift 2 ;;
    --brokers-root) BROKERS_ROOT="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --keep-running) KEEP_RUNNING=1; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown arg: $1"; usage ;;
  esac
done

# id=prd-{producerId}-srv-{originServer}-msg-{n}
ORIGIN_PRODUCER=""
ORIGIN_SERVER=""
if [[ "${DUP_ID}" =~ ^prd-([0-9]+)-srv-([0-9]+)-msg- ]]; then
  ORIGIN_PRODUCER="${BASH_REMATCH[1]}"
  ORIGIN_SERVER="${BASH_REMATCH[2]}"
fi

if [[ -z "${DUP_ID}" ]]; then
  echo "ERROR: --id (_AMQ_DUPL_ID) is required"
  usage
fi

if [[ ! -d "${BROKERS_ROOT}" ]]; then
  echo "ERROR: brokers root not found: ${BROKERS_ROOT}"
  echo "Run the soak test once so instances exist under tests/soak-tests/target/scaledown/"
  exit 1
fi

mkdir -p "${OUT_DIR}"
SUMMARY="${OUT_DIR}/SUMMARY.txt"

{
  echo "=== ScaleDownSoak duplicate investigation ==="
  echo "time: $(date -Iseconds)"
  echo "_AMQ_DUPL_ID (id): ${DUP_ID}"
  echo "origin (from id): producer=${ORIGIN_PRODUCER:-?} → broker-${ORIGIN_SERVER:-?}"
  echo "first delivery: consumer=${FIRST_CONSUMER_ID:-?} @ broker-${FIRST_SERVER_ID:-?}  deliveryCount=${FIRST_DELIVERY_COUNT:-?}  jmsMessageId=${FIRST_JMS_ID:-?}"
  echo "dup delivery:   consumer=${CONSUMER_ID:-?} @ broker-${SERVER_ID:-?}  deliveryCount=${DELIVERY_COUNT:-?}  jmsMessageId=${JMS_ID:-?}"
  echo "brokers: ${BROKERS_ROOT}"
  echo "out: ${OUT_DIR}"
  echo
} | tee "${SUMMARY}"

if [[ -n "${DELIVERY_COUNT}" && -n "${FIRST_JMS_ID}" && -n "${JMS_ID}" ]]; then
  if [[ "${DELIVERY_COUNT}" =~ ^[0-9]+$ ]] && { [[ "${DELIVERY_COUNT}" -gt 1 ]] || [[ "${JMS_ID}" == "${FIRST_JMS_ID}" ]]; }; then
    echo "test-rule: broker-consumer (deliveryCount>1 or same JMSMessageID)" | tee -a "${SUMMARY}"
  else
    echo "test-rule: not-broker-consumer (new JMSMessageID + deliveryCount<=1 → scale-down/new copy)" | tee -a "${SUMMARY}"
  fi
  echo | tee -a "${SUMMARY}"
fi

# Build queueID → queueName TSV from data-print QUEUE_BINDING rows (bindings journal).
# Output columns: queueID<TAB>queueName
build_queue_id_map() {
  local out="$1"
  shift
  : >"${out}"
  local f
  for f in "$@"; do
    [[ -f "${f}" ]] || continue
    awk '
      BEGIN { n = 0; ns = 0 }
      {
        lines[++n] = $0
        if ($0 ~ /^[│|](Add|Update|Delete|Commit|Rollback|Prepare|Event)/) {
          starts[++ns] = n
        }
      }
      END {
        for (si = 1; si <= ns; si++) {
          s = starts[si]
          e = (si < ns) ? starts[si + 1] - 1 : n
          split(lines[s], cells, /[│|]/)
          rtype = cells[4]
          gsub(/^[[:space:]]+|[[:space:]]+$/, "", rtype)
          if (index(rtype, "QUEUE_BINDING") == 0) continue
          decoded = ""
          for (j = s; j <= e; j++) decoded = decoded lines[j]
          # Flatten table wrapping / box-drawing so long SF queue names stay intact.
          # Note: do not put UTF-8 │ inside [] — gawk treats those as separate bytes.
          gsub(/│/, "", decoded)
          gsub(/\|/, "", decoded)
          gsub(/[[:space:]]+/, " ", decoded)
          # QueueConfiguration [id=11, name=TEST_REDIST, address=...
          if (match(decoded, /QueueConfiguration \[id=([0-9]+), name=([^,]+), address=/, m)) {
            qid = m[1]
            qname = m[2]
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", qname)
            # table wrap often inserts a space after a trailing '.' (sf.my-cluster. <uuid>)
            gsub(/\.[[:space:]]+/, ".", qname)
            if (qid != "" && qname != "" && !(qid in seen)) {
              seen[qid] = 1
              print qid "\t" qname
            }
          }
        }
      }
    ' "${f}"
  done | sort -t $'\t' -k1,1n -u >>"${out}"
}

# Build nodeUUID → serverId TSV from data-print banners ("Server's ID=<uuid>").
# Output columns: nodeUUID<TAB>serverId
build_node_id_map() {
  local out="$1"
  shift
  : >"${out}"
  local f name sid uuid
  for f in "$@"; do
    [[ -f "${f}" ]] || continue
    name="$(basename "${f}")"
    name="${name%-print.txt}"
    name="${name%-print-reclaimed.txt}"
    [[ "${name}" == broker-* ]] || continue
    sid="${name#broker-}"
    uuid="$(sed -n "s/.*Server's ID=\([a-f0-9-][a-f0-9-]*\).*/\1/p" "${f}" | head -1)"
    if [[ -n "${uuid}" && "${sid}" =~ ^[0-9]+$ ]]; then
      printf '%s\t%s\n' "${uuid}" "${sid}"
    fi
  done | sort -t $'\t' -k2,2n -u >>"${out}"
}

# Rewrite queueID=N → queueID=N, queueName=...[, serverId=...] using queue + node maps.
# SnF queues ($.artemis.internal.sf.<cluster>.<nodeUUID>) get serverId from the node UUID suffix.
# Skips occurrences that already have ", queueName=".
annotate_queue_ids() {
  local infile="$1"
  local queue_map="$2"
  local node_map="$3"
  local outfile="$4"
  if [[ ! -s "${queue_map}" ]]; then
    cp "${infile}" "${outfile}"
    return
  fi
  awk -F '\t' -v nodemap="${node_map}" '
    BEGIN {
      if (nodemap != "") {
        while ((getline < nodemap) > 0) {
          split($0, kv, "\t")
          if (kv[1] != "" && kv[2] != "") nodeToServer[kv[1]] = kv[2]
        }
        close(nodemap)
      }
    }
    function snf_server(qname,   m) {
      if (index(qname, ".artemis.internal.sf.") == 0) return ""
      if (match(qname, /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/, m)) {
        if (m[1] in nodeToServer) return nodeToServer[m[1]]
      }
      return ""
    }
    function annotate_token(qid,   a, sid) {
      if (!(qid in qname)) return "queueID=" qid
      a = "queueID=" qid ", queueName=" qname[qid]
      sid = snf_server(qname[qid])
      if (sid != "") a = a ", serverId=" sid
      return a
    }
    FNR == NR {
      if (NF >= 2) qname[$1] = $2
      next
    }
    {
      line = $0
      out = ""
      while (match(line, /queueID=[0-9]+/)) {
        out = out substr(line, 1, RSTART - 1)
        token = substr(line, RSTART, RLENGTH)
        qid = substr(token, 9)
        rest = substr(line, RSTART + RLENGTH)
        if (rest ~ /^, queueName=/) {
          out = out token
        } else {
          out = out annotate_token(qid)
        }
        line = rest
      }
      print out line
    }
  ' "${queue_map}" "${infile}" >"${outfile}"
}

# data print wraps each journal row across multiple table lines. A hit on _AMQ_DUPL_ID is often
# a continuation line; this extracts the full wrapped row plus related rows sharing the same ID.
extract_print_records() {
  local file="$1"
  local needle="$2"
  local out="$3"
  awk -v needle="${needle}" '
    # Whole-token match: needle must not be bordered by word chars, so
    # prd-0-srv-0-msg-325 does not match prd-0-srv-0-msg-3252.
    function has_needle(s, nd,   off, p, abs, before, after) {
      off = 0
      while (1) {
        p = index(substr(s, off + 1), nd)
        if (p == 0) return 0
        abs = off + p
        before = (abs > 1) ? substr(s, abs - 1, 1) : ""
        after = substr(s, abs + length(nd), 1)
        if ((before == "" || before !~ /[A-Za-z0-9_]/) && (after == "" || after !~ /[A-Za-z0-9_]/))
          return 1
        off = abs
      }
    }
    BEGIN { n = 0 }
    {
      lines[++n] = $0
      # New journal op row: first cell after leading box-char is a non-empty Operation
      if ($0 ~ /^[│|](Add|Update|Delete|Commit|Rollback|Prepare|Event)/) {
        starts[++ns] = n
      }
    }
    END {
      for (i = 1; i <= n; i++) {
        if (!has_needle(lines[i], needle)) continue
        # find start of this wrapped record
        s = 1
        for (k = 1; k <= ns; k++) {
          if (starts[k] <= i) s = starts[k]
          else break
        }
        e = n
        for (k = 1; k <= ns; k++) {
          if (starts[k] > i) { e = starts[k] - 1; break }
        }
        block = ""
        for (j = s; j <= e; j++) block = block lines[j] ORS
        print "----- record containing " needle " (lines " s "-" e ") -----"
        printf "%s", block

        # broker / journal record id from the start line ID column (2nd cell)
        startLine = lines[s]
        split(startLine, cells, /[│|]/)
        # cells[1] empty before first │, cells[2]=Operation, cells[3]=ID
        rid = cells[3]
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", rid)
        if (rid ~ /^[0-9]+$/) {
          print "----- related rows with ID=" rid " -----"
          for (j = 1; j <= n; j++) {
            split(lines[j], c2, /[│|]/)
            id2 = c2[3]
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", id2)
            if (id2 == rid) {
              # print this start row and its continuations
              s2 = j
              e2 = n
              for (k = 1; k <= ns; k++) {
                if (starts[k] > j) { e2 = starts[k] - 1; break }
              }
              # only emit from starts
              isStart = 0
              for (k = 1; k <= ns; k++) if (starts[k] == j) isStart = 1
              if (!isStart) continue
              for (t = s2; t <= e2; t++) print lines[t]
              print ""
            }
          }
        }
        print ""
      }
    }
  ' "${file}" >"${out}"
}

extract_exp_messages() {
  local file="$1"
  local needle="$2"
  local out="$3"
  # Best-effort: extract <messages>...</messages> (or <message>...</message>) blocks containing needle
  awk -v needle="${needle}" '
    # Whole-token match (see extract_print_records) so msg-325 != msg-3252.
    function has_needle(s, nd,   off, p, abs, before, after) {
      off = 0
      while (1) {
        p = index(substr(s, off + 1), nd)
        if (p == 0) return 0
        abs = off + p
        before = (abs > 1) ? substr(s, abs - 1, 1) : ""
        after = substr(s, abs + length(nd), 1)
        if ((before == "" || before !~ /[A-Za-z0-9_]/) && (after == "" || after !~ /[A-Za-z0-9_]/))
          return 1
        off = abs
      }
    }
    BEGIN { RS=""; ORS="\n\n" }
    has_needle($0, needle) { print "----- XML block containing " needle " -----"; print }
  ' "${file}" >"${out}" 2>/dev/null || true
  # Fallback line-oriented if RS="" did not help (single-line-ish XML)
  if [[ ! -s "${out}" ]] && grep -Fw -q -- "${needle}" "${file}" 2>/dev/null; then
    {
      echo "----- XML lines containing ${needle} (with context) -----"
      grep -Fw -n -C 20 -- "${needle}" "${file}" || true
    } >"${out}"
  fi
}

# Emit TSV timeline rows for events related to needle from one data-print file.
# Columns: epoch\tbroker\tjournal_file\tfile_id\tline\top\tid\ttype\ttx\ttimestamp\tuserMessageID\tmessageID\tdetail
# Related = wrapped records containing needle, plus other ops with the same journal ID, plus their TX commits.
# Optional 4th/5th args: queueID→name and nodeUUID→serverId TSV maps for DETAIL annotations.
emit_timeline_rows() {
  local broker="$1"
  local file="$2"
  local needle="$3"
  local queue_map="${4:-}"
  local node_map="${5:-}"
  awk -v broker="${broker}" -v needle="${needle}" -v mapfile="${queue_map}" -v nodemap="${node_map}" '
    BEGIN {
      n = 0
      ns = 0
      journal_file = "-"
      file_id = 0
      OFS = "\t"
      if (mapfile != "") {
        while ((getline < mapfile) > 0) {
          split($0, kv, "\t")
          if (kv[1] != "" && kv[2] != "") qname[kv[1]] = kv[2]
        }
        close(mapfile)
      }
      if (nodemap != "") {
        while ((getline < nodemap) > 0) {
          split($0, kv, "\t")
          if (kv[1] != "" && kv[2] != "") nodeToServer[kv[1]] = kv[2]
        }
        close(nodemap)
      }
    }
    # Whole-token match (see extract_print_records) so msg-325 != msg-3252.
    function has_needle(s, nd,   off, p, abs, before, after) {
      off = 0
      while (1) {
        p = index(substr(s, off + 1), nd)
        if (p == 0) return 0
        abs = off + p
        before = (abs > 1) ? substr(s, abs - 1, 1) : ""
        after = substr(s, abs + length(nd), 1)
        if ((before == "" || before !~ /[A-Za-z0-9_]/) && (after == "" || after !~ /[A-Za-z0-9_]/))
          return 1
        off = abs
      }
    }
    function snf_server(qn,   m) {
      if (index(qn, ".artemis.internal.sf.") == 0) return ""
      if (match(qn, /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/, m)) {
        if (m[1] in nodeToServer) return nodeToServer[m[1]]
      }
      return ""
    }
    function queue_anno(qid,   a, sid) {
      a = "queueID=" qid
      if (qid in qname) {
        a = a ", queueName=" qname[qid]
        sid = snf_server(qname[qid])
        if (sid != "") a = a ", serverId=" sid
      }
      return a
    }
    /^#JournalFileImpl:/ {
      journal_file = $0
      sub(/^#JournalFileImpl:[[:space:]]*\(/, "", journal_file)
      sub(/[[:space:]]+id = .*/, "", journal_file)
      if (match($0, /id = ([0-9]+)/, m)) file_id = m[1] + 0
      else file_id++
    }
    {
      lines[++n] = $0
      if ($0 ~ /^[│|](Add|Update|Delete|Commit|Rollback|Prepare|Event)/) {
        starts[++ns] = n
        start_file[ns] = journal_file
        start_fid[ns] = file_id
      }
    }
    END {
      # Pass 1: find record IDs and TXs touched by needle
      for (i = 1; i <= n; i++) {
        if (!has_needle(lines[i], needle)) continue
        sidx = start_index_for(i)
        if (sidx < 1) continue
        s = starts[sidx]
        e = end_for(sidx)
        decoded = ""
        for (j = s; j <= e; j++) decoded = decoded lines[j]
        split(lines[s], cells, /[│|]/)
        rid = trim(cells[3])
        tx = trim(cells[5])
        if (rid ~ /^[0-9]+$/) interesting_id[rid] = 1
        if (tx ~ /^[0-9]+$/) interesting_tx[tx] = 1
        if (match(decoded, /messageID=([0-9]+)/, m)) interesting_id[m[1]] = 1
      }
      # Pass 2: also mark TXs used by interesting IDs (refs/acks)
      for (si = 1; si <= ns; si++) {
        s = starts[si]
        split(lines[s], cells, /[│|]/)
        rid = trim(cells[3])
        tx = trim(cells[5])
        if (rid in interesting_id && tx ~ /^[0-9]+$/) interesting_tx[tx] = 1
      }
      if (length(interesting_id) == 0 && length(interesting_tx) == 0) exit

      # Pass 3: emit events
      for (si = 1; si <= ns; si++) {
        s = starts[si]
        e = end_for(si)
        split(lines[s], cells, /[│|]/)
        op = trim(cells[2])
        rid = trim(cells[3])
        rtype = trim(cells[4])
        tx = trim(cells[5])
        include = 0
        if (rid in interesting_id) include = 1
        # whole TX (includes DUPLICATE_ID sibling of ADD_MESSAGE, commits, etc.)
        if (tx in interesting_tx) include = 1
        decoded = ""
        for (j = s; j <= e; j++) decoded = decoded lines[j]
        if (!include) {
          if (!has_needle(decoded, needle)) continue
        }
        decoded = ""
        for (j = s; j <= e; j++) decoded = decoded lines[j]
        ts = ""
        if (match(decoded, /timestamp=([^,]+)/, m)) {
          ts = m[1]
          gsub(/^[[:space:]]+|[[:space:]]+$/, "", ts)
        }
        umid = ""
        if (match(decoded, /userMessageID=([^;]+)/, m)) umid = m[1]
        mid = ""
        if (match(decoded, /messageID=([0-9]+)/, m)) mid = m[1]
        detail = rtype
        if (has_needle(decoded, needle)) detail = detail " [has _AMQ_DUPL_ID]"
        if (match(decoded, /DeliveryCountUpdateEncoding \[queueID=([0-9]+), count=([0-9]+)\]/, m)) {
          detail = detail " " queue_anno(m[1]) " count=" m[2]
        } else if (match(decoded, /QueueEncoding \[queueID=([0-9]+)\]/, m)) {
          detail = detail " " queue_anno(m[1])
        } else if (match(decoded, /queueID=([0-9]+)/, m)) {
          detail = detail " " queue_anno(m[1])
        }
        if (index(decoded, "_AMQ_ROUTE_TO") > 0 || index(decoded, ".artemis.internal.sf.") > 0) {
          detail = detail " [SF_ROUTE]"
        }
        gsub(/\t/, " ", detail)
        gsub(/\t/, " ", ts)
        # Never emit empty fields (keeps TSV columns stable)
        if (rid == "") rid = "-"
        if (rtype == "") rtype = "-"
        if (tx == "") tx = "-"
        if (ts == "") ts = "-"
        if (umid == "") umid = "-"
        if (mid == "") mid = "-"
        if (detail == "") detail = "-"
        print broker, start_file[si], start_fid[si], s, op, rid, rtype, tx, ts, umid, mid, detail
      }
    }
    function start_index_for(i,   k, sidx) {
      sidx = 0
      for (k = 1; k <= ns; k++) {
        if (starts[k] <= i) sidx = k
        else break
      }
      return sidx
    }
    function end_for(sidx,   e) {
      e = n
      if (sidx < ns) e = starts[sidx + 1] - 1
      return e
    }
    function trim(s) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", s)
      return s
    }
  ' "${file}"
}

# Convert timeline TSV to sorted human table using message timestamp when present.
# Input columns: broker jfile fid line op rid rtype tx ts umid mid detail
# ACK/Delete/Commit often lack timestamp → inherit from ADD_MESSAGE on same broker+id or broker+tx.
sort_timeline() {
  local raw="$1"
  local out="$2"
  local filled sorted_tmp
  filled="$(mktemp)"
  sorted_tmp="$(mktemp)"

  blank() { [[ -z "$1" || "$1" == "-" ]]; }

  # Pass 1: learn timestamps by broker+messageId and broker+tx
  declare -A TS_BY_ID TS_BY_TX UMID_BY_ID
  while IFS=$'\t' read -r broker jfile fid line op rid rtype tx ts umid mid detail; do
    [[ -z "${broker}" ]] && continue
    if ! blank "${ts}"; then
      if ! blank "${mid}"; then
        TS_BY_ID["${broker}:${mid}"]="${ts}"
      fi
      if [[ "${rid}" =~ ^[0-9]+$ ]]; then
        TS_BY_ID["${broker}:${rid}"]="${ts}"
      fi
      if [[ "${tx}" =~ ^[0-9]+$ ]]; then
        TS_BY_TX["${broker}:${tx}"]="${ts}"
      fi
    fi
    if ! blank "${umid}"; then
      if ! blank "${mid}"; then
        UMID_BY_ID["${broker}:${mid}"]="${umid}"
      fi
      if [[ "${rid}" =~ ^[0-9]+$ ]]; then
        UMID_BY_ID["${broker}:${rid}"]="${umid}"
      fi
    fi
  done < "${raw}"

  # Pass 2: fill ts/umid from id, then propagate to TX map, then fill remaining via TX
  filled1="$(mktemp)"
  while IFS=$'\t' read -r broker jfile fid line op rid rtype tx ts umid mid detail; do
    [[ -z "${broker}" ]] && continue
    if blank "${ts}"; then
      if ! blank "${mid}" && [[ -n "${TS_BY_ID[${broker}:${mid}]+x}" ]]; then
        ts="${TS_BY_ID[${broker}:${mid}]}"
      elif [[ "${rid}" =~ ^[0-9]+$ && -n "${TS_BY_ID[${broker}:${rid}]+x}" ]]; then
        ts="${TS_BY_ID[${broker}:${rid}]}"
      fi
    fi
    if blank "${umid}"; then
      if ! blank "${mid}" && [[ -n "${UMID_BY_ID[${broker}:${mid}]+x}" ]]; then
        umid="${UMID_BY_ID[${broker}:${mid}]}"
      elif [[ "${rid}" =~ ^[0-9]+$ && -n "${UMID_BY_ID[${broker}:${rid}]+x}" ]]; then
        umid="${UMID_BY_ID[${broker}:${rid}]}"
      fi
    fi
    if ! blank "${ts}" && [[ "${tx}" =~ ^[0-9]+$ ]]; then
      TS_BY_TX["${broker}:${tx}"]="${ts}"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${broker}" "${jfile}" "${fid}" "${line}" "${op}" \
      "${rid:-"-"}" "${rtype:-"-"}" "${tx:-"-"}" "${ts:-"-"}" "${umid:-"-"}" "${mid:-"-"}" "${detail:-"-"}"
  done < "${raw}" >"${filled1}"

  while IFS=$'\t' read -r broker jfile fid line op rid rtype tx ts umid mid detail; do
    [[ -z "${broker}" ]] && continue
    if blank "${ts}" && [[ "${tx}" =~ ^[0-9]+$ && -n "${TS_BY_TX[${broker}:${tx}]+x}" ]]; then
      ts="${TS_BY_TX[${broker}:${tx}]}"
    fi
    epoch=0
    if ! blank "${ts}"; then
      epoch="$(date -d "${ts}" +%s 2>/dev/null || echo 0)"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${epoch}" "${broker}" "${fid}" "${line}" "${op}" \
      "${rid}" "${rtype}" "${tx}" "${ts}" "${umid}" "${mid}" \
      "${jfile}" "${detail}"
  done < "${filled1}" >"${filled}"
  rm -f "${filled1}"

  sort -t $'\t' -k1,1n -k2,2 -k3,3n -k4,4n "${filled}" >"${sorted_tmp}"

  {
    printf '%-12s %-10s %6s %6s %-10s %6s %-22s %6s %-26s %-38s %s\n' \
      "EPOCH" "BROKER" "FILE#" "LINE" "OP" "ID" "RECORD_TYPE" "TX" "TIMESTAMP" "USER_MESSAGE_ID" "DETAIL"
    printf '%s\n' "------------------------------------------------------------------------------------------------------------------------------------------------------------------"
    while IFS=$'\t' read -r epoch broker fid line op rid rtype tx ts umid mid jfile detail; do
      short_type="${rtype}"
      case "${short_type}" in
        *'('*')'*) short_type="${short_type#*(}"; short_type="${short_type%)*}" ;;
      esac
      printf '%-12s %-10s %6s %6s %-10s %6s %-22s %6s %-26s %-38s %s\n' \
        "${epoch}" "${broker}" "${fid}" "${line}" "${op}" "${rid}" "${short_type}" "${tx}" "${ts}" "${umid}" "${detail}"
    done < "${sorted_tmp}"
  } >"${out}"
  rm -f "${filled}" "${sorted_tmp}"
}

# Rebuild logical message path from timeline.raw.tsv + consumer hop args.
# Input columns: broker jfile fid line op rid rtype tx ts umid mid detail
build_message_path() {
  local raw="$1"
  local out="$2"
  local origin_prod="$3"
  local origin_srv="$4"
  local first_c="$5"
  local first_s="$6"
  local dup_c="$7"
  local dup_s="$8"
  local first_dc="$9"
  local dup_dc="${10}"
  local first_jms="${11}"
  local dup_jms="${12}"
  local dup_id="${13}"

  {
    echo "=== MESSAGE PATH ==="
    echo "logical id (_AMQ_DUPL_ID): ${dup_id}"
    echo
    echo "--- Intended send path ---"
    if [[ -n "${origin_prod}" || -n "${origin_srv}" ]]; then
      echo "  producer ${origin_prod:-?}  →  broker-${origin_srv:-?}  (encoded in id)"
    else
      echo "  (could not parse prd-*-srv-*-msg-* from id)"
    fi
    echo
    echo "--- Consumer hops (from test) ---"
    echo "  1st:  consumer ${first_c:-?} @ broker-${first_s:-?}   deliveryCount=${first_dc:-?}   jmsMessageId=${first_jms:-?}"
    echo "  DUP:  consumer ${dup_c:-?} @ broker-${dup_s:-?}   deliveryCount=${dup_dc:-?}   jmsMessageId=${dup_jms:-?}"
    if [[ -n "${first_s}" && -n "${dup_s}" ]]; then
      if [[ "${first_s}" == "${dup_s}" ]]; then
        echo "  → both deliveries from the SAME broker (broker-${first_s})"
      else
        echo "  → deliveries from DIFFERENT brokers (broker-${first_s} then broker-${dup_s})"
      fi
    fi
    if [[ -n "${first_c}" && -n "${dup_c}" ]]; then
      if [[ "${first_c}" == "${dup_c}" ]]; then
        echo "  → same consumer thread received both"
      else
        echo "  → different consumer threads (${first_c} then ${dup_c})"
      fi
    fi
    if [[ -n "${first_jms}" && -n "${dup_jms}" && "${first_jms}" != "?" && "${dup_jms}" != "?" ]]; then
      if [[ "${first_jms}" == "${dup_jms}" ]]; then
        echo "  → same JMSMessageID (same broker Message.userMessageID / redelivery of one copy)"
      else
        echo "  → DIFFERENT JMSMessageID (two broker message instances delivered)"
      fi
    fi
    echo
    echo "--- Journal copies (per broker messageID) ---"
    echo "  lifecycle keys: ADD → REF → UPDATE_DELIVERY_COUNT → ACK → Delete"
    echo "  UPDATE_DELIVERY_COUNT = broker recorded a delivery to a consumer (often before consumer ACK)"
    echo "  ACK in same TX as another ADD = migrate/redistribution ACK (not a consumer ACK)"
    echo
  } >"${out}"

  if [[ ! -s "${raw}" ]]; then
    {
      echo "  (no journal events for this id — fully compacted or never stored)"
      echo
      echo "--- Duplication hypothesis ---"
      echo "  Insufficient journal evidence. Rely on consumer hops above."
    } >>"${out}"
    return
  fi

  # Emit per-broker copy lifecycles via awk, then append hypothesis in bash.
  # __STATS__: total_copies nb_brokers multi_local unacked_delivery migrate_after_delivery sf_route
  awk -F '\t' -v first_s="${first_s}" -v dup_s="${dup_s}" -v origin_srv="${origin_srv}" '
    function short_type(t,  s) {
      s = t
      if (match(s, /\(([^)]+)\)/, m)) return m[1]
      return s
    }
    function copy_key(broker, mid, rid,  cand) {
      cand = mid
      if (cand == "" || cand == "-") cand = rid
      if (cand == "" || cand == "-") return ""
      return broker SUBSEP cand
    }
    {
      broker = $1; fid = $3; line = $4; op = $5; rid = $6; rtype = $7; tx = $8; ts = $9; umid = $10; mid = $11; detail = $12
      n++
      B[n]=broker; FID[n]=fid; LINE[n]=line; OP[n]=op; RID[n]=rid; RTYPE[n]=rtype
      TX[n]=tx; TS[n]=ts; UMID[n]=umid; MID[n]=mid; DET[n]=detail
      st = short_type(rtype)
      if (st == "ADD_MESSAGE_PROTOCOL" || index(rtype, "ADD_MESSAGE") > 0) {
        key_mid = mid
        if (key_mid == "" || key_mid == "-") key_mid = rid
        if (key_mid != "" && key_mid != "-") {
          k = broker SUBSEP key_mid
          if (!(k in seen_add)) {
            seen_add[k] = 1
            add_order[++na] = k
            ADD_BROKER[k] = broker
            ADD_MID[k] = key_mid
            ADD_FID[k] = fid
            ADD_LINE[k] = line
            ADD_TX[k] = tx
            ADD_TS[k] = ts
            ADD_UMID[k] = umid
            copy_idx[broker]++
            ADD_ORDINAL[k] = copy_idx[broker]
            if (index(detail, "[SF_ROUTE]") > 0) {
              has_sf[k] = 1
              sf_route = 1
            }
            if (tx ~ /^[0-9]+$/) add_by_tx[broker SUBSEP tx] = k
          }
        }
      }
    }
    END {
      for (i = 1; i <= n; i++) {
        broker = B[i]; rid = RID[i]; mid = MID[i]; st = short_type(RTYPE[i]); tx = TX[i]; detail = DET[i]
        k = copy_key(broker, mid, rid)
        if (k == "" || !(k in ADD_MID)) continue
        if (st == "ADD_REF") {
          has_ref[k] = 1
        } else if (st == "UPDATE_DELIVERY_COUNT") {
          has_dc[k] = 1
          dc_line[k] = LINE[i]
          if (match(detail, /count=([0-9]+)/, m)) dc_count[k] = m[1]
          else dc_count[k] = "?"
          unacked_delivery = 1
        } else if (st == "ACKNOWLEDGE_REF") {
          has_ack[k] = 1
          ACK_TX[k] = tx
          # migrate ACK: same TX as ADD of a *different* mid on this broker
          if (tx ~ /^[0-9]+$/ && ((broker SUBSEP tx) in add_by_tx)) {
            other = add_by_tx[broker SUBSEP tx]
            if (other != k) {
              migrate_ack[k] = 1
              migrate_to[k] = ADD_MID[other]
              if (k in has_dc) migrate_after_delivery = 1
            }
          }
        } else if (OP[i] == "Delete" || st ~ /Delete/) {
          has_del[k] = 1
        }
        if (index(detail, "[SF_ROUTE]") > 0) {
          has_sf[k] = 1
          sf_route = 1
        }
      }

      for (ai = 1; ai <= na; ai++) {
        k = add_order[ai]
        broker = ADD_BROKER[k]
        if (!(broker in printed_hdr)) {
          printed_hdr[broker] = 1
          brokers[++nb] = broker
          printf "  %s", broker
          mark = ""
          if (origin_srv != "" && broker == ("broker-" origin_srv)) mark = mark " [ORIGIN send]"
          if (first_s != "" && broker == ("broker-" first_s)) mark = mark " [1st consumer]"
          if (dup_s != "" && broker == ("broker-" dup_s)) mark = mark " [DUP consumer]"
          if (mark != "") printf "%s", mark
          printf "\n"
        }
        life = "ADD"
        if (has_ref[k]) life = life " → REF"
        if (has_dc[k]) life = life " → UPDATE_DELIVERY_COUNT(count=" dc_count[k] ")"
        if (has_ack[k]) {
          if (k in migrate_ack) life = life " → ACK(migrate→mid=" migrate_to[k] ",tx=" ACK_TX[k] ")"
          else life = life " → ACK"
        }
        if (has_del[k]) life = life " → Delete"
        else if (has_ack[k]) life = life " (no Delete seen)"
        else if (has_dc[k]) life = life " (delivered, still unacked in dump)"
        else life = life " (still live / no ACK in dump)"

        copy_n = ADD_ORDINAL[k]
        tag = ""
        if (copy_n > 1) tag = tag "  *** COPY #" copy_n " on this broker ***"
        if (has_sf[k]) tag = tag "  [SF_ROUTE]"
        if (has_dc[k] && (k in migrate_ack)) {
          tag = tag "  *** delivered to consumer then migrated while unacked ***"
        } else if (has_dc[k] && !(k in has_ack)) {
          tag = tag "  *** delivered, no ACK in dump ***"
        }

        printf "    copy#%d  mid=%-8s  file=#%s L%s  tx=%s  umid=%s\n", \
          copy_n, ADD_MID[k], ADD_FID[k], ADD_LINE[k], ADD_TX[k], ADD_UMID[k]
        printf "            lifecycle: %s%s\n", life, tag
      }

      print ""
      print "--- Cross-broker copy map ---"
      multi_local = 0
      for (bi = 1; bi <= nb; bi++) {
        b = brokers[bi]
        c = copy_idx[b]
        printf "  %s: %d ADD_MESSAGE copy/copies\n", b, c
        if (c > 1) multi_local++
      }
      printf "  total ADD_MESSAGE copies across cluster: %d on %d broker(s)\n", na, nb
      if (!unacked_delivery) unacked_delivery = 0
      if (!migrate_after_delivery) migrate_after_delivery = 0
      if (!sf_route) sf_route = 0
      print "__STATS__ " na " " nb " " multi_local " " unacked_delivery " " migrate_after_delivery " " sf_route
    }
  ' "${raw}" >>"${out}"

  # Parse stats line and write hypothesis
  local stats total_copies nb_brokers multi_local unacked_delivery migrate_after_delivery sf_route path_tmp
  stats="$(grep '^__STATS__' "${out}" || true)"
  path_tmp="$(mktemp)"
  grep -v '^__STATS__' "${out}" >"${path_tmp}"
  mv "${path_tmp}" "${out}"
  total_copies="$(echo "${stats}" | awk '{print $2}')"
  nb_brokers="$(echo "${stats}" | awk '{print $3}')"
  multi_local="$(echo "${stats}" | awk '{print $4}')"
  unacked_delivery="$(echo "${stats}" | awk '{print $5}')"
  migrate_after_delivery="$(echo "${stats}" | awk '{print $6}')"
  sf_route="$(echo "${stats}" | awk '{print $7}')"
  total_copies="${total_copies:-0}"
  nb_brokers="${nb_brokers:-0}"
  multi_local="${multi_local:-0}"
  unacked_delivery="${unacked_delivery:-0}"
  migrate_after_delivery="${migrate_after_delivery:-0}"
  sf_route="${sf_route:-0}"

  {
    echo "--- Duplication hypothesis ---"
    local same_jms=0
    if [[ -n "${first_jms}" && -n "${dup_jms}" && "${first_jms}" != "?" && "${dup_jms}" != "?" && "${first_jms}" == "${dup_jms}" ]]; then
      same_jms=1
    fi

    # Journal movement (how the message got to the 2nd broker) — not the same as dup root cause
    local movement=""
    if [[ "${migrate_after_delivery}" -gt 0 ]]; then
      movement="after UPDATE_DELIVERY_COUNT, an ACK shared a TX with a new ADD (migrate/redistribution of still-unacked message)"
      if [[ "${sf_route}" -gt 0 ]]; then
        movement="${movement}; new ADD has SF/_AMQ_ROUTE_TO"
      fi
    elif [[ "${multi_local}" -gt 0 ]]; then
      movement="LOCAL_RECOPY: 2+ ADD_MESSAGE on one broker (scale-down/bridge rewrite)"
    elif [[ "${nb_brokers}" -gt 1 ]]; then
      movement="CROSS_NODE: copies on ${nb_brokers} brokers"
    elif [[ "${nb_brokers}" -eq 1 ]]; then
      movement="SINGLE_NODE store only"
    else
      movement="NO_JOURNAL ADD_MESSAGE found"
    fi
    echo "  journal movement: ${movement}"

    if [[ -n "${first_s}" && -n "${dup_s}" && "${first_s}" != "${dup_s}" ]]; then
      echo "  consume: first on broker-${first_s}, dup on broker-${dup_s}."
    elif [[ -n "${first_s}" && -n "${dup_s}" ]]; then
      echo "  consume: both deliveries from broker-${first_s}."
    fi

    # Root-cause priority: unacked consumer delivery outweighs cross-node hops
    if [[ "${unacked_delivery}" -gt 0 ]]; then
      echo "  likely point of duplication: broker-consumer"
      echo "    evidence: UPDATE_DELIVERY_COUNT on a copy before it left that broker → consumer already received it"
      echo "    but no consumer ACK was durable yet, so the broker still owned the message."
      if [[ "${migrate_after_delivery}" -gt 0 ]]; then
        echo "    then the unacked message was migrated/redistributed (ACK+new ADD in one TX)"
        if [[ -n "${first_s}" && -n "${dup_s}" && "${first_s}" != "${dup_s}" ]]; then
          echo "    and delivered again on broker-${dup_s} (same JMSMessageID is expected for move/redeliver)."
        fi
        echo "    broker-broker move is the *second-delivery mechanism*, not the root cause of the dup."
      elif [[ -n "${dup_dc}" && "${dup_dc}" =~ ^[0-9]+$ && "${dup_dc}" -gt 1 ]]; then
        echo "    second hop looks like classic redelivery (deliveryCount=${dup_dc})."
      fi
    elif [[ "${same_jms}" -eq 1 && -n "${dup_dc}" && "${dup_dc}" =~ ^[0-9]+$ && "${dup_dc}" -gt 1 ]]; then
      echo "  likely point of duplication: broker-consumer (same JMSMessageID, deliveryCount>1 → redelivery)"
    elif [[ -n "${first_s}" && -n "${dup_s}" && "${first_s}" != "${dup_s}" && "${nb_brokers}" -gt 1 && "${same_jms}" -eq 0 ]]; then
      echo "  likely point of duplication: broker-broker"
      echo "    evidence: different brokers + different JMSMessageID, no UPDATE_DELIVERY_COUNT before move."
    elif [[ "${multi_local}" -gt 0 && "${same_jms}" -eq 0 ]]; then
      echo "  likely point of duplication: broker-broker (local recopy / scale-down rewrite delivered as a new instance)"
    elif [[ "${same_jms}" -eq 1 ]]; then
      echo "  likely point of duplication: broker-consumer (same JMSMessageID; prefer redelivery/unacked-delivery over pure broker-broker)"
      echo "    tip: look for UPDATE_DELIVERY_COUNT on the 1st-consumer broker in TIMELINE/records."
    else
      echo "  ambiguous: check TIMELINE for UPDATE_DELIVERY_COUNT vs second ADD before the dup ACK."
    fi

    if [[ -n "${origin_srv}" && -n "${first_s}" && "${origin_srv}" != "${first_s}" ]]; then
      echo "  note: first consume was NOT on origin broker-${origin_srv} (moved before first delivery)."
    fi
    echo
    echo "Read TIMELINE.txt for full chronological journal detail of these hops."
  } >>"${out}"
}

stop_brokers() {
  local broker
  for broker in "${BROKERS_ROOT}"/broker-*; do
    [[ -d "${broker}" ]] || continue
    if [[ -x "${broker}/bin/artemis" ]]; then
      echo "Stopping $(basename "${broker}") ..."
      "${broker}/bin/artemis" stop >/dev/null 2>&1 || true
    fi
  done
  sleep 2
}

if [[ "${KEEP_RUNNING}" -eq 0 ]]; then
  echo "Stopping brokers (halt usually leaves them running)..." | tee -a "${SUMMARY}"
  stop_brokers
else
  echo "Keeping brokers running; data print will use --f" | tee -a "${SUMMARY}"
fi

FORCE_ARGS=()
if [[ "${KEEP_RUNNING}" -eq 1 ]]; then
  FORCE_ARGS+=(--f)
fi

echo | tee -a "${SUMMARY}"

TIMELINE_RAW="${OUT_DIR}/timeline.raw.tsv"
: >"${TIMELINE_RAW}"
NODE_MAP="${OUT_DIR}/node-ids.tsv"

# Pass 1: dump journals for every broker (needed before nodeUUID→serverId map is complete).
echo "=== Dumping broker journals ===" | tee -a "${SUMMARY}"
for broker in "${BROKERS_ROOT}"/broker-*; do
  [[ -d "${broker}" ]] || continue
  name="$(basename "${broker}")"
  print_out="${OUT_DIR}/${name}-print.txt"
  print_reclaimed="${OUT_DIR}/${name}-print-reclaimed.txt"
  exp_out="${OUT_DIR}/${name}-exp.xml"

  echo "--- ${name} (dump) ---" | tee -a "${SUMMARY}"

  if [[ ! -x "${broker}/bin/artemis" ]]; then
    echo "  skip: no artemis CLI" | tee -a "${SUMMARY}"
    continue
  fi

  ( cd "${broker}" && ./bin/artemis data print "${FORCE_ARGS[@]}" --output "${print_out}" ) \
    >"${OUT_DIR}/${name}-print.log" 2>&1 || echo "  data print failed (see ${name}-print.log)" | tee -a "${SUMMARY}"

  ( cd "${broker}" && ./bin/artemis data print "${FORCE_ARGS[@]}" --reclaimed --output "${print_reclaimed}" ) \
    >"${OUT_DIR}/${name}-print-reclaimed.log" 2>&1 || true

  ( cd "${broker}" && ./bin/artemis data exp "${FORCE_ARGS[@]}" --output "${exp_out}" ) \
    >"${OUT_DIR}/${name}-exp.log" 2>&1 || true
done

build_node_id_map "${NODE_MAP}" "${OUT_DIR}"/broker-*-print.txt
if [[ -s "${NODE_MAP}" ]]; then
  echo "node map ($(wc -l < "${NODE_MAP}") brokers) → node-ids.tsv" | tee -a "${SUMMARY}"
  sed 's/^/  /' "${NODE_MAP}" | tee -a "${SUMMARY}"
  echo | tee -a "${SUMMARY}"
fi

# Pass 2: extract / annotate using complete queue + node maps.
echo "=== Per-broker journal search (key=${DUP_ID}) ===" | tee -a "${SUMMARY}"
for broker in "${BROKERS_ROOT}"/broker-*; do
  [[ -d "${broker}" ]] || continue
  name="$(basename "${broker}")"
  print_out="${OUT_DIR}/${name}-print.txt"
  print_reclaimed="${OUT_DIR}/${name}-print-reclaimed.txt"
  exp_out="${OUT_DIR}/${name}-exp.xml"
  records_out="${OUT_DIR}/${name}-records.txt"

  echo "--- ${name} ---" | tee -a "${SUMMARY}"

  if [[ ! -f "${print_out}" && ! -f "${print_reclaimed}" ]]; then
    echo "  skip: no data print" | tee -a "${SUMMARY}"
    continue
  fi

  queue_map="${OUT_DIR}/${name}-queues.tsv"
  build_queue_id_map "${queue_map}" "${print_out}" "${print_reclaimed}"
  if [[ -s "${queue_map}" ]]; then
    echo "  queue map ($(wc -l < "${queue_map}") queues) → ${name}-queues.tsv" | tee -a "${SUMMARY}"
  fi

  : >"${records_out}"
  found=0
  # Prefer reclaimed for timeline (includes ack/delete history); fall back to surviving print.
  timeline_src=""
  if [[ -f "${print_reclaimed}" ]] && grep -Fw -q -- "${DUP_ID}" "${print_reclaimed}" 2>/dev/null; then
    timeline_src="${print_reclaimed}"
  elif [[ -f "${print_out}" ]] && grep -Fw -q -- "${DUP_ID}" "${print_out}" 2>/dev/null; then
    timeline_src="${print_out}"
  fi
  if [[ -n "${timeline_src}" ]]; then
    emit_timeline_rows "${name}" "${timeline_src}" "${DUP_ID}" "${queue_map}" "${NODE_MAP}" >>"${TIMELINE_RAW}" || true
  fi

  records_tmp="$(mktemp)"
  for f in "${print_reclaimed}" "${print_out}"; do
    if [[ -f "${f}" ]] && grep -Fw -q -- "${DUP_ID}" "${f}" 2>/dev/null; then
      found=1
      {
        echo "##### source: $(basename "${f}") #####"
        extract_print_records "${f}" "${DUP_ID}" /dev/stdout
      } >>"${records_tmp}"
    fi
  done
  if [[ -f "${exp_out}" ]] && grep -Fw -q -- "${DUP_ID}" "${exp_out}" 2>/dev/null; then
    found=1
    {
      echo "##### source: $(basename "${exp_out}") #####"
      extract_exp_messages "${exp_out}" "${DUP_ID}" /dev/stdout
    } >>"${records_tmp}"
  fi
  if [[ -s "${records_tmp}" ]]; then
    annotate_queue_ids "${records_tmp}" "${queue_map}" "${NODE_MAP}" "${records_out}"
  else
    : >"${records_out}"
  fi
  rm -f "${records_tmp}"

  if [[ "${found}" -eq 0 ]]; then
    echo "  no _AMQ_DUPL_ID match (acked+compacted, or never stored on this node)" | tee -a "${SUMMARY}"
  else
    echo "  full records → ${name}-records.txt" | tee -a "${SUMMARY}"
    # Summarize distinct broker messageID= from extracted records
    msg_ids=$(grep -oE 'messageID=[0-9]+' "${records_out}" 2>/dev/null | sort -u || true)
    if [[ -n "${msg_ids}" ]]; then
      echo "  broker messageID= values:" | tee -a "${SUMMARY}"
      echo "${msg_ids}" | sed 's/^/    /' | tee -a "${SUMMARY}"
      count=$(echo "${msg_ids}" | grep -c . || true)
      if [[ "${count}" -gt 1 ]]; then
        echo "  → multiple broker messageIDs for same _AMQ_DUPL_ID on ${name}" | tee -a "${SUMMARY}"
      else
        echo "  → single broker messageID on ${name}" | tee -a "${SUMMARY}"
      fi
    fi
    # Preview first extracted record in SUMMARY (truncated)
    echo "  preview:" | tee -a "${SUMMARY}"
    sed -n '1,40p' "${records_out}" | sed 's/^/    /' | tee -a "${SUMMARY}"
    if [[ "$(wc -l < "${records_out}")" -gt 40 ]]; then
      echo "    ... (see ${name}-records.txt for full extract)" | tee -a "${SUMMARY}"
    fi
  fi
  echo | tee -a "${SUMMARY}"
done

echo "=== Cross-broker presence of id=${DUP_ID} ===" | tee -a "${SUMMARY}"
nodes_with_id=0
for records in "${OUT_DIR}"/broker-*-records.txt; do
  [[ -f "${records}" ]] || continue
  if [[ -s "${records}" ]]; then
    nodes_with_id=$((nodes_with_id + 1))
    echo "  $(basename "${records}" | sed 's/-records.txt//')" | tee -a "${SUMMARY}"
  fi
done
if [[ "${nodes_with_id}" -gt 1 ]]; then
  echo "→ id found on ${nodes_with_id} brokers: likely scale-down/redistribution across nodes" | tee -a "${SUMMARY}"
elif [[ "${nodes_with_id}" -eq 1 ]]; then
  echo "→ id found on a single broker: consistent with local redelivery or single-node store" | tee -a "${SUMMARY}"
else
  echo "→ id not found in any dump (fully acked/compacted before halt)" | tee -a "${SUMMARY}"
fi
echo | tee -a "${SUMMARY}"

PATH_OUT="${OUT_DIR}/MESSAGE_PATH.txt"
echo "=== Message path (primary) ===" | tee -a "${SUMMARY}"
build_message_path "${TIMELINE_RAW}" "${PATH_OUT}" \
  "${ORIGIN_PRODUCER}" "${ORIGIN_SERVER}" \
  "${FIRST_CONSUMER_ID}" "${FIRST_SERVER_ID}" \
  "${CONSUMER_ID}" "${SERVER_ID}" \
  "${FIRST_DELIVERY_COUNT}" "${DELIVERY_COUNT}" \
  "${FIRST_JMS_ID}" "${JMS_ID}" \
  "${DUP_ID}"
tee -a "${SUMMARY}" < "${PATH_OUT}"
echo | tee -a "${SUMMARY}"

TIMELINE_OUT="${OUT_DIR}/TIMELINE.txt"
echo "=== Sorted timeline (detail) ===" | tee -a "${SUMMARY}"
if [[ -s "${TIMELINE_RAW}" ]]; then
  sort_timeline "${TIMELINE_RAW}" "${TIMELINE_OUT}"
  tee -a "${SUMMARY}" < "${TIMELINE_OUT}"
  echo | tee -a "${SUMMARY}"
  echo "Timeline sort: epoch(timestamp) → broker → journal file# → line" | tee -a "${SUMMARY}"
else
  echo "(no timeline events extracted)" | tee -a "${SUMMARY}"
  : >"${TIMELINE_OUT}"
fi
echo | tee -a "${SUMMARY}"

{
  echo "=== Artifacts ==="
  echo "  MESSAGE_PATH.txt   reconstructed copy path + duplication hypothesis (start here)"
  echo "  TIMELINE.txt       chronological journal events"
  echo "  timeline.raw.tsv   machine-readable events"
  echo "  *-records.txt      FULL journal/XML extracts (queueID + queueName [+ serverId for SnF])"
  echo "  *-queues.tsv       queueID → queueName map from bindings"
  echo "  node-ids.tsv       nodeUUID → serverId (from Server's ID= banners)"
  echo "  *-print*.txt       raw data print"
  echo "  *-exp.xml          XML export"
  echo "  SUMMARY.txt        this report"
} | tee -a "${SUMMARY}"

echo
echo "Done. Path: ${PATH_OUT}"
echo "Summary: ${SUMMARY}"
echo "Timeline: ${TIMELINE_OUT}"
