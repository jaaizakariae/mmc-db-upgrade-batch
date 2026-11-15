#!/usr/bin/env bash
# shift_sql_file_sequences.sh
# Compatible with macOS default Bash 3.2
#
# Supports:
# - Shift file sequences using an offset
# - Renumber all SQL files sequentially from 0001

set -Eeuo pipefail

TARGET_DIR=""
OFFSET=""
START_SEQUENCE="21"
MODE="--preview"
RENUMBER_MODE="false"

usage() {
  echo "Usage:"
  echo "  $0 --folder <sql-folder> --offset <number> [--start-sequence <number>] [--preview|--apply]"
  echo "  $0 --folder <sql-folder> --renumber [--preview|--apply]"
  echo
  echo "Modes:"
  echo "  --offset <number>          Add/subtract a value from file sequences."
  echo "  --renumber                 Rename all SQL files sequentially from 0001."
  echo
  echo "Required options:"
  echo "  --folder <path>            Folder containing the .sql files"
  echo
  echo "Optional options for offset mode:"
  echo "  --start-sequence <number>  Process only files with sequence >= this number. Default: 21"
  echo
  echo "Execution options:"
  echo "  --preview                  Display the rename plan only. Default mode"
  echo "  --apply                    Apply the renaming"
  echo
  echo "Examples:"
  echo "  $0 --folder /path/to/sql --start-sequence 17 --offset -1 --preview"
  echo "  $0 --folder /path/to/sql --start-sequence 21 --offset +2 --apply"
  echo "  $0 --folder /path/to/sql --offset 10 --preview"
  echo "  $0 --folder /path/to/sql --renumber --preview"
  echo "  $0 --folder /path/to/sql --renumber --apply"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --folder)
      TARGET_DIR="${2:-}"
      shift 2
      ;;
    --offset)
      OFFSET="${2:-}"
      shift 2
      ;;
    --start-sequence)
      START_SEQUENCE="${2:-}"
      shift 2
      ;;
    --renumber)
      RENUMBER_MODE="true"
      shift
      ;;
    --preview)
      MODE="--preview"
      shift
      ;;
    --apply)
      MODE="--apply"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ -z "${TARGET_DIR}" ]; then
  echo "ERROR: --folder is required." >&2
  usage
  exit 1
fi

if [ ! -d "${TARGET_DIR}" ]; then
  echo "ERROR: Folder does not exist: ${TARGET_DIR}" >&2
  exit 1
fi

if [ "${RENUMBER_MODE}" = "true" ] && [ -n "${OFFSET}" ]; then
  echo "ERROR: --renumber cannot be used with --offset." >&2
  exit 1
fi

if [ "${RENUMBER_MODE}" = "false" ] && [ -z "${OFFSET}" ]; then
  echo "ERROR: Either --offset or --renumber is required." >&2
  usage
  exit 1
fi

if [ "${RENUMBER_MODE}" = "false" ]; then
  if ! printf '%s' "${OFFSET}" | grep -Eq '^[+-]?[0-9]+$'; then
    echo "ERROR: --offset must be a whole number, for example +1, 2 or -3." >&2
    exit 1
  fi

  if ! printf '%s' "${START_SEQUENCE}" | grep -Eq '^[0-9]+$'; then
    echo "ERROR: --start-sequence must be a positive integer." >&2
    exit 1
  fi
fi

TEMP_PREFIX=".__sql_sequence_shift_tmp__"
PLAN_FILE="$(mktemp "${TMPDIR:-/tmp}/sql_sequence_shift.XXXXXX")"

cleanup() {
  rm -f "${PLAN_FILE}"
}

trap cleanup EXIT

if find "${TARGET_DIR}" -maxdepth 1 -type f -name "${TEMP_PREFIX}*" | grep -q .; then
  echo "ERROR: Temporary rename files already exist in ${TARGET_DIR}." >&2
  echo "Restore or remove them before running this script." >&2
  exit 1
fi

if [ "${RENUMBER_MODE}" = "true" ]; then
  renumber_sequence=1

  find "${TARGET_DIR}" -maxdepth 1 -type f -name "*.sql" -print \
    | sed 's#^.*/##' \
    | sort -t '_' -k1,1n -k2,2 \
    | while IFS= read -r filename; do

        case "${filename}" in
          [0-9][0-9][0-9][0-9]_*.sql)
            suffix="$(printf '%s' "${filename}" | cut -c6-)"

            if [ "${renumber_sequence}" -gt 9999 ]; then
              echo "ERROR: More than 9999 SQL files cannot be renumbered." >&2
              exit 1
            fi

            new_filename="$(printf '%04d_%s' "${renumber_sequence}" "${suffix}")"
            printf '%s\t%s\n' "${filename}" "${new_filename}" >> "${PLAN_FILE}"

            renumber_sequence=$((renumber_sequence + 1))
            ;;
        esac
      done
else
  find "${TARGET_DIR}" -maxdepth 1 -type f -name "*.sql" -print \
    | sed 's#^.*/##' \
    | sort -t '_' -k1,1n -k2,2 \
    | while IFS= read -r filename; do

        case "${filename}" in
          [0-9][0-9][0-9][0-9]_*.sql)
            old_sequence_text="$(printf '%s' "${filename}" | cut -c1-4)"
            old_sequence=$((10#${old_sequence_text}))

            if [ "${old_sequence}" -ge "${START_SEQUENCE}" ]; then
              suffix="$(printf '%s' "${filename}" | cut -c6-)"
              new_sequence=$((old_sequence + OFFSET))

              if [ "${new_sequence}" -lt 0 ] || [ "${new_sequence}" -gt 9999 ]; then
                echo "ERROR: Invalid target sequence for ${filename}: ${new_sequence}" >&2
                exit 1
              fi

              new_filename="$(printf '%04d_%s' "${new_sequence}" "${suffix}")"
              printf '%s\t%s\n' "${filename}" "${new_filename}" >> "${PLAN_FILE}"
            fi
            ;;
        esac
      done
fi

if [ ! -s "${PLAN_FILE}" ]; then
  if [ "${RENUMBER_MODE}" = "true" ]; then
    echo "No SQL files found with format 0000_name.sql."
  else
    echo "No SQL files found with sequence >= $(printf '%04d' "${START_SEQUENCE}")."
  fi
  exit 0
fi

duplicate_target="$(cut -f2 "${PLAN_FILE}" | sort | uniq -d | head -n 1)"

if [ -n "${duplicate_target}" ]; then
  echo "ERROR: Multiple source files would produce the same target filename:" >&2
  grep -F "$(printf '\t%s' "${duplicate_target}")" "${PLAN_FILE}" >&2
  exit 1
fi

while IFS="$(printf '\t')" read -r source_filename target_filename; do
  target_path="${TARGET_DIR}/${target_filename}"

  if [ -e "${target_path}" ] && [ "${source_filename}" != "${target_filename}" ]; then
    if ! grep -Fq "$(printf '%s\t' "${target_filename}")" "${PLAN_FILE}"; then
      echo "ERROR: Target file already exists and is not included in this rename operation:" >&2
      echo "  ${target_path}" >&2
      exit 1
    fi
  fi
done < "${PLAN_FILE}"

total_files="$(wc -l < "${PLAN_FILE}" | tr -d ' ')"

echo "Folder : ${TARGET_DIR}"
echo "Mode   : ${MODE}"

if [ "${RENUMBER_MODE}" = "true" ]; then
  echo "Action : Renumber all SQL files from 0001"
else
  echo "Action         : Shift sequences"
  echo "Start sequence : $(printf '%04d' "${START_SEQUENCE}")"
  echo "Offset         : ${OFFSET}"
fi

echo
echo "Rename preview:"
echo "----------------------------------------------------------------------------"

while IFS="$(printf '\t')" read -r source_filename target_filename; do
  printf '%-60s -> %s\n' "${source_filename}" "${target_filename}"
done < "${PLAN_FILE}"

echo "----------------------------------------------------------------------------"
echo "Total files: ${total_files}"

if [ "${MODE}" = "--preview" ]; then
  echo
  echo "Preview completed. No file was renamed."
  echo "Run again with --apply to rename the files."
  exit 0
fi

echo
echo "Applying renaming..."

temporary_index=0

while IFS="$(printf '\t')" read -r source_filename target_filename; do
  temporary_index=$((temporary_index + 1))

  source_path="${TARGET_DIR}/${source_filename}"
  temporary_path="${TARGET_DIR}/${TEMP_PREFIX}${temporary_index}.sql"

  mv "${source_path}" "${temporary_path}"
done < "${PLAN_FILE}"

temporary_index=0

while IFS="$(printf '\t')" read -r source_filename target_filename; do
  temporary_index=$((temporary_index + 1))

  temporary_path="${TARGET_DIR}/${TEMP_PREFIX}${temporary_index}.sql"
  target_path="${TARGET_DIR}/${target_filename}"

  mv "${temporary_path}" "${target_path}"
done < "${PLAN_FILE}"

if [ "${RENUMBER_MODE}" = "true" ]; then
  echo "SUCCESS: ${total_files} SQL file(s) renumbered from 0001."
else
  echo "SUCCESS: ${total_files} SQL file(s) renamed with offset ${OFFSET}."
fi