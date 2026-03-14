# harness-aimp

Quick restore commands:

```bash
cd /Users/linzeran/code/2026-zn/harnees_aimp
./scripts/restore_env.sh
```

Quick stop commands:

```bash
cd /Users/linzeran/code/2026-zn/harnees_aimp
./scripts/stop_env.sh
```

Default debug console:

- `http://127.0.0.1:8080/console/debug`

Notes:

- `restore_env.sh` starts `L1/L2/L3/L4/L5/L6` in dependency order.
- `AGENT_MODEL_HUB_QWEN35_27B_API_KEY` defaults to `test` if not set.
- Runtime logs are written to `.runtime/`.
