# Specification: Comprehensive JSON Logging System (Commands & Services)

## Overview
A centralized, non-interactive JSON logging system to record command executions, service-layer activity, and database operations. Logs will be persisted to the filesystem with a strict total size limit of 10MB.

## Functional Requirements
- **Multi-Layer Logging:** Implement JSON logging for:
  - **Command Layer:** Inputs, parameters, and outputs of all commands.
  - **Service Layer:** Key business logic methods (e.g., `MapService`, `CombatEngine`).
  - **Database Layer:** SQL queries and results (via Exposed/SQLite).
- **JSON Formatting:** Every log entry must be a valid JSON object containing:
  - `timestamp`: ISO 8601 date and time.
  - `layer`: The system layer (COMMAND, SERVICE, DATABASE).
  - `component`: The name of the specific class or command.
  - `operation`: The method name or action performed.
  - `data`: A structured map of inputs/parameters or outputs/results.
  - `status`: Success or Failure status (including error details).
- **Log Persistence:** Logs must be written exclusively to a file (e.g., `data/logs/luna-json.log`). No JSON logs should be printed to the terminal (STDOUT).
- **File Size Management:** Total size of all log files in `data/logs/` must not exceed 10MB. Implement log rotation (e.g., 5 files of 2MB each).

## Non-Functional Requirements
- **Performance:** Asynchronous logging to minimize overhead on critical execution paths.
- **Consistency:** Use a unified JSON schema for all layers.
- **Reliability:** The core RPG logic should be unaffected by logging failures.

## Acceptance Criteria
- [ ] Commands (e.g., /explore) generate a JSON log entry in the log file with input/output data.
- [ ] Service methods (e.g., `MapService.move`) generate a JSON log entry in the log file.
- [ ] Database queries generate a JSON log entry in the log file.
- [ ] The `data/logs/` directory remains below 10MB.
- [ ] No JSON logs appear in the console (terminal).

## Out of Scope
- **Terminal Visualization:** Real-time log monitoring via the terminal is not required.
- **Log Exporting:** External integrations (e.g., ELK stack) are not in scope.
