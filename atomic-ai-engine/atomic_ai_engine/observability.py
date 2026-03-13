import json
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
OBS_LOG = ROOT / "data" / "capability_invocations.log"


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def append_event(event):
    OBS_LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(OBS_LOG, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(event, ensure_ascii=False) + "\n")


def read_events(limit=200):
    if not OBS_LOG.exists():
        return []
    with open(OBS_LOG, "r", encoding="utf-8") as fh:
        rows = [line.strip() for line in fh.readlines() if line.strip()]
    return [json.loads(row) for row in rows[-limit:]]


def summarize(events):
    summary = {
        "total_calls": len(events),
        "success_calls": 0,
        "error_calls": 0,
        "average_duration_ms": 0,
        "capabilities": {},
        "error_codes": {},
    }
    if not events:
        return summary
    total_duration = 0
    for event in events:
        total_duration += event.get("duration_ms", 0)
        if event.get("status") == "success":
            summary["success_calls"] += 1
        else:
            summary["error_calls"] += 1
        cap = event.get("capability_code", "unknown")
        item = summary["capabilities"].setdefault(cap, {
            "calls": 0,
            "success": 0,
            "error": 0,
            "average_duration_ms": 0,
            "last_status": None,
        })
        item["calls"] += 1
        item["last_status"] = event.get("status")
        item.setdefault("_duration_sum", 0)
        item["_duration_sum"] += event.get("duration_ms", 0)
        if event.get("status") == "success":
            item["success"] += 1
        else:
            item["error"] += 1
        for error in event.get("errors", []):
            code = error.get("code", "UNKNOWN")
            summary["error_codes"][code] = summary["error_codes"].get(code, 0) + 1
    summary["average_duration_ms"] = int(total_duration / len(events))
    for item in summary["capabilities"].values():
        item["average_duration_ms"] = int(item.pop("_duration_sum") / item["calls"])
    return summary


def overview(limit=200):
    events = read_events(limit=limit)
    return {
        "status": "UP",
        "service": "atomic-ai-engine",
        "summary": summarize(events),
        "recent_calls": events[-20:],
    }


def capability_overview(capability_code, limit=200):
    events = [event for event in read_events(limit=limit) if event.get("capability_code") == capability_code]
    return {
        "capability_code": capability_code,
        "summary": summarize(events),
        "recent_calls": events[-20:],
    }
