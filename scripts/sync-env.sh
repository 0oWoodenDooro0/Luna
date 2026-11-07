#!/usr/bin/env bash
# ==============================================================================
# Luna Deployment Diff & Safe Sync Tooling (scripts/sync-env.sh)
#
# Audits target .env files against canonical .env.example, prints visual diff
# reports, and safely synchronizes missing environment variables with backups.
# ==============================================================================

set -euo pipefail

MODE="check"
ENV_FILE="${ENV_FILE:-.env}"
ENV_EXAMPLE="${ENV_EXAMPLE:-.env.example}"
USE_COLOR=true

if [ ! -t 1 ] || [ -n "${NO_COLOR:-}" ]; then
  USE_COLOR=false
fi

print_usage() {
  cat << 'EOF'
Usage: scripts/sync-env.sh [MODE] [OPTIONS]

Modes:
  --check                 Audit target .env against .env.example (default)
  --sync                  Safely append missing variables to target .env with backup

Options:
  -f, --env-file <path>   Target .env file path (default: .env or $ENV_FILE)
  -e, --example <path>    Canonical .env.example file path (default: .env.example or $ENV_EXAMPLE)
  --no-color              Disable ANSI color output
  -h, --help              Display this help message
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check)
      MODE="check"
      shift
      ;;
    --sync)
      MODE="sync"
      shift
      ;;
    -f|--env-file|--env)
      if [ $# -lt 2 ]; then
        echo "Error: Missing value for $1" >&2
        exit 1
      fi
      ENV_FILE="$2"
      shift 2
      ;;
    --env-file=*|--env=*)
      ENV_FILE="${1#*=}"
      shift
      ;;
    -e|--example)
      if [ $# -lt 2 ]; then
        echo "Error: Missing value for $1" >&2
        exit 1
      fi
      ENV_EXAMPLE="$2"
      shift 2
      ;;
    --example=*)
      ENV_EXAMPLE="${1#*=}"
      shift
      ;;
    --no-color)
      USE_COLOR=false
      shift
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      echo "Error: Unknown argument: $1" >&2
      print_usage
      exit 1
      ;;
  esac
done

if [ "$USE_COLOR" = true ]; then
  COLOR_RED="\033[0;31m"
  COLOR_GREEN="\033[0;32m"
  COLOR_YELLOW="\033[1;33m"
  COLOR_CYAN="\033[0;36m"
  COLOR_BOLD="\033[1m"
  COLOR_RESET="\033[0m"
else
  COLOR_RED=""
  COLOR_GREEN=""
  COLOR_YELLOW=""
  COLOR_CYAN=""
  COLOR_BOLD=""
  COLOR_RESET=""
fi

if [ ! -f "$ENV_EXAMPLE" ]; then
  echo -e "${COLOR_RED}❌ Error: Canonical template '$ENV_EXAMPLE' not found.${COLOR_RESET}" >&2
  exit 1
fi

extract_schema_keys() {
  awk '
    /^# ==============================================================================/ { next }
    /^# Section [0-9]+/ { next }
    /^[A-Z0-9_]{3,}=/ || /^# [A-Z0-9_]{3,}=/ {
      line = $0
      match(line, /^(# )?([A-Z0-9_]+)=/, arr)
      key = arr[2]
      if (key != "EXAMPLE" && substr(key, 1, 7) != "SECTION") {
        print key
      }
    }
  ' "$ENV_EXAMPLE"
}

is_required_key() {
  local target="$1"
  awk -v target="$target" '
    BEGIN { req = 0; block = "" }
    /^# ==============================================================================/ { block = ""; req = 0; next }
    /^# Section [0-9]+/ { block = ""; req = 0; next }
    /^$/ { block = ""; req = 0; next }
    {
      if ($0 ~ /\[必填\]/) req = 1
      if ($0 ~ /\[選填\]/) req = 0
    }
    /^[A-Z0-9_]{3,}=/ || /^# [A-Z0-9_]{3,}=/ {
      line = $0
      match(line, /^(# )?([A-Z0-9_]+)=/, arr)
      if (arr[2] == target) {
        print req
        exit
      }
    }
  ' "$ENV_EXAMPLE"
}

get_default_val() {
  local target="$1"
  awk -v target="$target" '
    BEGIN { def_val = ""; block = "" }
    /^# ==============================================================================/ { block = ""; def_val = ""; next }
    /^# Section [0-9]+/ { block = ""; def_val = ""; next }
    /^$/ { block = ""; def_val = ""; next }
    {
      if ($0 ~ /^# 預設值[：:]/) {
        sub(/^# 預設值[：:][ \t]*/, "")
        def_val = $0
        sub(/^[ \t]+/, "", def_val)
        sub(/[ \t]+$/, "", def_val)
      }
    }
    /^[A-Z0-9_]{3,}=/ || /^# [A-Z0-9_]{3,}=/ {
      line = $0
      match(line, /^(# )?([A-Z0-9_]+)=/, arr)
      if (arr[2] == target) {
        if (def_val == "") {
          val = substr(line, index(line, "=") + 1)
          if (val !~ /^your_/ && val != "") {
            gsub(/^["'\'']|["'\'']$/, "", val)
            def_val = val
          }
        }
        print def_val
        exit
      }
    }
  ' "$ENV_EXAMPLE"
}

extract_block() {
  local target="$1"
  awk -v target="$target" '
    BEGIN { block = "" }
    /^# ==============================================================================/ { block = ""; next }
    /^# Section [0-9]+/ { block = ""; next }
    /^$/ { block = ""; next }
    {
      if (block == "") {
        block = $0
      } else {
        block = block "\n" $0
      }
    }
    /^[A-Z0-9_]{3,}=/ || /^# [A-Z0-9_]{3,}=/ {
      line = $0
      match(line, /^(# )?([A-Z0-9_]+)=/, arr)
      if (arr[2] == target) {
        print block
        exit
      }
    }
  ' "$ENV_EXAMPLE"
}

get_active_env_keys() {
  if [ ! -f "$ENV_FILE" ]; then
    return
  fi
  grep -E '^[A-Za-z0-9_]+=' "$ENV_FILE" 2>/dev/null | cut -d'=' -f1 | sed 's/^[ \t]*//;s/[ \t]*$//' || true
}

get_declared_env_keys() {
  if [ ! -f "$ENV_FILE" ]; then
    return
  fi
  grep -E '^(#\s*)?[A-Za-z0-9_]+=' "$ENV_FILE" 2>/dev/null | sed -E 's/^(#\s*)?([A-Za-z0-9_]+)=.*/\2/' || true
}

is_recognized_key() {
  local key="$1"
  shift
  local schema_keys=("$@")

  for s in "${schema_keys[@]}"; do
    if [ "$key" = "$s" ]; then return 0; fi
  done

  case "$key" in
    DISCORD_APP_ID|clientId|luna.clientId|botToken|luna.botToken|clientSecret|luna.clientSecret|port|luna.port|MCP_PORT|mcp.port|baseUrl|luna.baseUrl|curtly.baseUrl|curtly.base_url|CURTLY_BASE_URL|PLUGINS_CURTLY_BASE_URL|redirectUri|REDIRECT_URI|luna.redirectUri|dbPath|mcp.dbPath|publicBaseUrl|mcp.publicBaseUrl)
      return 0
      ;;
    PATH|HOME|USER|LOGNAME|SHELL|PWD|OLDPWD|TERM|LANG|LC_ALL|LC_CTYPE|JAVA_HOME|HOSTNAME|SHLVL|_|LS_COLORS|EDITOR|PAGER|TZ|NODE_VERSION|CI|CLASSPATH)
      return 0
      ;;
    PLUGINS_*_ENABLED|plugins.*.enabled|curtly.*|CURTLY_*|summarizer.*|summary.*|SUMMARY_*|gemini.*|GEMINI_*|youtube.*|YOUTUBE_*|undercover.*|UNDERCOVER_*|mcp.*|MCP_*)
      return 0
      ;;
  esac

  return 1
}

get_candidate_keys() {
  local sk="$1"
  local candidates=("$sk")
  case "$sk" in
    DISCORD_CLIENT_ID)
      candidates+=("DISCORD_APP_ID" "clientId" "luna.clientId")
      ;;
    DISCORD_TOKEN)
      candidates+=("botToken" "luna.botToken")
      ;;
    DISCORD_CLIENT_SECRET)
      candidates+=("clientSecret" "luna.clientSecret")
      ;;
    PORT)
      candidates+=("port" "luna.port")
      ;;
    BASE_URL)
      candidates+=("baseUrl" "luna.baseUrl" "curtly.baseUrl" "curtly.base_url" "CURTLY_BASE_URL" "PLUGINS_CURTLY_BASE_URL")
      ;;
    DISCORD_REDIRECT_URI)
      candidates+=("redirectUri" "REDIRECT_URI" "luna.redirectUri")
      ;;
    MCP_PORT)
      candidates+=("mcp.port" "port")
      ;;
    MCP_DB_PATH)
      candidates+=("dbPath" "mcp.dbPath")
      ;;
    MCP_PUBLIC_BASE_URL)
      candidates+=("publicBaseUrl" "mcp.publicBaseUrl")
      ;;
  esac
  echo "${candidates[@]}"
}

do_check() {
  if [ ! -f "$ENV_FILE" ]; then
    echo -e "${COLOR_RED}❌ Error: Environment file '$ENV_FILE' does not exist.${COLOR_RESET}" >&2
    exit 1
  fi

  local schema_keys=()
  while IFS= read -r k; do
    [ -n "$k" ] && schema_keys+=("$k")
  done < <(extract_schema_keys)

  local active_keys=()
  while IFS= read -r k; do
    [ -n "$k" ] && active_keys+=("$k")
  done < <(get_active_env_keys)

  local missing_required=()
  local missing_optional=()

  for sk in "${schema_keys[@]}"; do
    local is_req
    is_req=$(is_required_key "$sk")

    local found=false
    local cand_str
    cand_str=$(get_candidate_keys "$sk")
    for c in $cand_str; do
      for ak in "${active_keys[@]}"; do
        if [ "$ak" = "$c" ]; then
          # Check that value is non-blank
          local val
          val=$(grep -E "^[ \t]*${c}=" "$ENV_FILE" | head -n 1 | cut -d'=' -f2- | sed 's/^[ \t]*//;s/[ \t]*$//' | tr -d "\"'")
          if [ -n "$val" ]; then
            found=true
            break 2
          fi
        fi
      done
    done

    if [ "$found" = false ]; then
      if [ "$is_req" = "1" ]; then
        missing_required+=("$sk")
      else
        local dval
        dval=$(get_default_val "$sk")
        missing_optional+=("$sk::$dval")
      fi
    fi
  done

  local unknown_keys=()
  for ak in "${active_keys[@]}"; do
    if ! is_recognized_key "$ak" "${schema_keys[@]}"; then
      unknown_keys+=("$ak")
    fi
  done

  echo -e "${COLOR_BOLD}🔍 Environment Configuration Audit: $ENV_FILE${COLOR_RESET}"
  echo "===================================================================="

  if [ ${#missing_required[@]} -gt 0 ]; then
    echo -e "${COLOR_RED}${COLOR_BOLD}❌ Missing Required (${#missing_required[@]}):${COLOR_RESET}"
    for r in "${missing_required[@]}"; do
      echo -e "${COLOR_RED}   - $r${COLOR_RESET}"
    done
    echo ""
  fi

  if [ ${#missing_optional[@]} -gt 0 ]; then
    echo -e "${COLOR_YELLOW}${COLOR_BOLD}⚠️ Missing Optional (${#missing_optional[@]}):${COLOR_RESET}"
    for opt in "${missing_optional[@]}"; do
      local k="${opt%%::*}"
      local d="${opt#*::}"
      if [ -n "$d" ]; then
        echo -e "${COLOR_YELLOW}   - $k (default: $d)${COLOR_RESET}"
      else
        echo -e "${COLOR_YELLOW}   - $k${COLOR_RESET}"
      fi
    done
    echo ""
  fi

  if [ ${#unknown_keys[@]} -gt 0 ]; then
    echo -e "${COLOR_CYAN}${COLOR_BOLD}🗑️ Deprecated/Unknown (${#unknown_keys[@]}):${COLOR_RESET}"
    for u in "${unknown_keys[@]}"; do
      echo -e "${COLOR_CYAN}   - $u${COLOR_RESET}"
    done
    echo ""
  fi

  echo "===================================================================="

  if [ ${#missing_required[@]} -gt 0 ]; then
    echo -e "${COLOR_RED}${COLOR_BOLD}Audit Failed: ${#missing_required[@]} required environment variable(s) missing.${COLOR_RESET}"
    exit 1
  elif [ ${#missing_optional[@]} -gt 0 ] || [ ${#unknown_keys[@]} -gt 0 ]; then
    echo -e "${COLOR_GREEN}${COLOR_BOLD}Audit Passed with warnings: All required environment variables are set.${COLOR_RESET}"
    exit 0
  else
    echo -e "${COLOR_GREEN}${COLOR_BOLD}✅ All environment variables match $ENV_EXAMPLE specifications!${COLOR_RESET}"
    exit 0
  fi
}

do_sync() {
  if [ ! -f "$ENV_FILE" ]; then
    echo -e "${COLOR_RED}❌ Error: Target environment file '$ENV_FILE' does not exist.${COLOR_RESET}" >&2
    exit 1
  fi

  if [ ! -w "$ENV_FILE" ]; then
    echo -e "${COLOR_RED}❌ Error: Cannot write to environment file '$ENV_FILE' (Permission denied).${COLOR_RESET}" >&2
    exit 1
  fi

  local schema_keys=()
  while IFS= read -r k; do
    [ -n "$k" ] && schema_keys+=("$k")
  done < <(extract_schema_keys)

  local declared_keys=()
  while IFS= read -r k; do
    [ -n "$k" ] && declared_keys+=("$k")
  done < <(get_declared_env_keys)

  local missing_keys=()
  for sk in "${schema_keys[@]}"; do
    local found=false
    local cand_str
    cand_str=$(get_candidate_keys "$sk")
    for c in $cand_str; do
      for dk in "${declared_keys[@]}"; do
        if [ "$dk" = "$c" ]; then
          found=true
          break 2
        fi
      done
    done
    if [ "$found" = false ]; then
      missing_keys+=("$sk")
    fi
  done

  echo -e "${COLOR_BOLD}🔄 Synchronizing environment configuration: $ENV_FILE${COLOR_RESET}"

  if [ ${#missing_keys[@]} -eq 0 ]; then
    echo -e "${COLOR_GREEN}✅ Environment file is already in sync with $ENV_EXAMPLE. No changes made.${COLOR_RESET}"
    exit 0
  fi

  local timestamp
  timestamp=$(date +%Y%m%d%H%M%S)
  local backup_file="${ENV_FILE}.bak.${timestamp}"

  cp "$ENV_FILE" "$backup_file"
  echo -e "📦 Created backup: ${COLOR_CYAN}$backup_file${COLOR_RESET}"

  # Ensure target file ends with newline before appending
  if [ -s "$ENV_FILE" ]; then
    local last_char
    last_char=$(tail -c 1 "$ENV_FILE" 2>/dev/null || true)
    if [ "$last_char" != "" ]; then
      echo "" >> "$ENV_FILE"
    fi
  fi

  echo "" >> "$ENV_FILE"
  echo "# ==============================================================================" >> "$ENV_FILE"
  echo "# Synced from $ENV_EXAMPLE at $(date)" >> "$ENV_FILE"
  echo "# ==============================================================================" >> "$ENV_FILE"

  for mk in "${missing_keys[@]}"; do
    echo "" >> "$ENV_FILE"
    local block
    block=$(extract_block "$mk")
    if [ -n "$block" ]; then
      echo "$block" >> "$ENV_FILE"
    fi
  done

  echo -e "➕ Appended ${COLOR_BOLD}${#missing_keys[@]}${COLOR_RESET} missing variable(s):"
  for mk in "${missing_keys[@]}"; do
    local dval
    dval=$(get_default_val "$mk")
    if [ -n "$dval" ]; then
      echo "   - $mk (default: $dval)"
    else
      echo "   - $mk"
    fi
  done

  echo -e "${COLOR_GREEN}${COLOR_BOLD}✅ Environment synchronization complete.${COLOR_RESET}"
  exit 0
}

case "$MODE" in
  check)
    do_check
    ;;
  sync)
    do_sync
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    exit 1
    ;;
esac
