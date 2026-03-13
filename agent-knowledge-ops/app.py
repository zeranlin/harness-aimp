import json
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

PORT = 8004


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


class KnowledgeOpsStore:
    def __init__(self):
        self.knowledge_bases = {
            "kb-policy-docs": {
                "knowledge_base_id": "kb-policy-docs",
                "name": "policy-docs",
                "domain": "procurement-policy",
                "tenant_id": "public",
                "status": "ready",
                "version": "v3",
                "owner": "knowledge-ops",
                "documents": 128,
                "published": True,
                "updated_at": now_iso(),
            },
            "kb-bid-cases": {
                "knowledge_base_id": "kb-bid-cases",
                "name": "bid-cases",
                "domain": "bid-risk",
                "tenant_id": "public",
                "status": "syncing",
                "version": "v1",
                "owner": "knowledge-ops",
                "documents": 42,
                "published": False,
                "updated_at": now_iso(),
            },
        }
        self.pipeline_runs = {
            "run-daily-ingest": {
                "pipeline_run_id": "run-daily-ingest",
                "pipeline_type": "ingest",
                "name": "daily-ingest",
                "status": "success",
                "knowledge_base_id": "kb-policy-docs",
                "started_at": now_iso(),
                "ended_at": now_iso(),
                "inputs": {"documents": 12},
                "outputs": {"artifacts": 12, "chunks": 96},
            },
            "run-quality-check": {
                "pipeline_run_id": "run-quality-check",
                "pipeline_type": "quality-check",
                "name": "quality-check",
                "status": "success",
                "knowledge_base_id": "kb-policy-docs",
                "started_at": now_iso(),
                "ended_at": now_iso(),
                "inputs": {"documents": 128},
                "outputs": {"issues": 0},
            },
        }
        self.quality_reports = {
            "kb-policy-docs": {
                "knowledge_base_id": "kb-policy-docs",
                "version": "v3",
                "score": 98,
                "issues": [],
                "generated_at": now_iso(),
            },
            "kb-bid-cases": {
                "knowledge_base_id": "kb-bid-cases",
                "version": "v1",
                "score": 82,
                "issues": [
                    {"code": "STALE_SOURCE", "message": "source document update pending"}
                ],
                "generated_at": now_iso(),
            },
        }
        self.audit_records = [
            {
                "actor": "knowledge-ops",
                "action": "publish",
                "target_type": "knowledge_base",
                "target_id": "kb-policy-docs",
                "timestamp": now_iso(),
            },
            {
                "actor": "knowledge-ops",
                "action": "ingest",
                "target_type": "pipeline_run",
                "target_id": "run-daily-ingest",
                "timestamp": now_iso(),
            },
        ]
        self.lineage = {
            "kb-policy-docs": {
                "knowledge_base_id": "kb-policy-docs",
                "source_documents": ["policy-v3.pdf", "appendix-rules.docx"],
                "artifacts": ["artifact-policy-index-v3", "artifact-policy-rules-v3"],
                "consumers": ["L2.intelligent_qa", "L3.rule_engine"],
            },
            "kb-bid-cases": {
                "knowledge_base_id": "kb-bid-cases",
                "source_documents": ["case-batch-202603.csv"],
                "artifacts": ["artifact-bid-cases-index-v1"],
                "consumers": ["L2.compliance_review", "L3.evidence_chain_locate"],
            },
        }
        self.next_kb = 3
        self.next_run = 3

    def list_knowledge_bases(self):
        return list(self.knowledge_bases.values())

    def get_knowledge_base(self, knowledge_base_id):
        return self.knowledge_bases.get(knowledge_base_id)

    def create_knowledge_base(self, payload):
        knowledge_base_id = payload.get("knowledge_base_id") or f"kb-generated-{self.next_kb}"
        self.next_kb += 1
        item = {
            "knowledge_base_id": knowledge_base_id,
            "name": payload.get("name") or knowledge_base_id,
            "domain": payload.get("domain") or "general",
            "tenant_id": payload.get("tenant_id") or "public",
            "status": "draft",
            "version": "v1",
            "owner": payload.get("owner") or "knowledge-ops",
            "documents": int(payload.get("documents") or 0),
            "published": False,
            "updated_at": now_iso(),
        }
        self.knowledge_bases[knowledge_base_id] = item
        self.quality_reports[knowledge_base_id] = {
            "knowledge_base_id": knowledge_base_id,
            "version": "v1",
            "score": 75,
            "issues": [{"code": "NOT_PUBLISHED", "message": "knowledge base not published yet"}],
            "generated_at": now_iso(),
        }
        self.audit_records.insert(0, {
            "actor": item["owner"],
            "action": "create",
            "target_type": "knowledge_base",
            "target_id": knowledge_base_id,
            "timestamp": now_iso(),
        })
        self.lineage[knowledge_base_id] = {
            "knowledge_base_id": knowledge_base_id,
            "source_documents": [],
            "artifacts": [],
            "consumers": [],
        }
        return item

    def publish_knowledge_base(self, knowledge_base_id):
        item = self.knowledge_bases.get(knowledge_base_id)
        if not item:
            return None
        item["status"] = "ready"
        item["published"] = True
        item["updated_at"] = now_iso()
        report = self.quality_reports.get(knowledge_base_id)
        if report and any(issue.get("code") == "NOT_PUBLISHED" for issue in report.get("issues", [])):
            report["issues"] = [issue for issue in report["issues"] if issue.get("code") != "NOT_PUBLISHED"]
            report["score"] = max(report["score"], 85)
            report["generated_at"] = now_iso()
        self.audit_records.insert(0, {
            "actor": "knowledge-ops",
            "action": "publish",
            "target_type": "knowledge_base",
            "target_id": knowledge_base_id,
            "timestamp": now_iso(),
        })
        return item

    def list_pipeline_runs(self):
        return list(self.pipeline_runs.values())

    def get_pipeline_run(self, run_id):
        return self.pipeline_runs.get(run_id)

    def run_ingest(self, payload):
        knowledge_base_id = payload.get("knowledge_base_id") or "kb-policy-docs"
        kb = self.knowledge_bases.get(knowledge_base_id)
        if not kb:
            return None
        run_id = f"run-ingest-{self.next_run}"
        self.next_run += 1
        documents = int(payload.get("documents") or 1)
        kb["status"] = "syncing"
        kb["documents"] += documents
        kb["updated_at"] = now_iso()
        run = {
            "pipeline_run_id": run_id,
            "pipeline_type": "ingest",
            "name": payload.get("name") or "manual-ingest",
            "status": "success",
            "knowledge_base_id": knowledge_base_id,
            "started_at": now_iso(),
            "ended_at": now_iso(),
            "inputs": {"documents": documents},
            "outputs": {"artifacts": documents, "chunks": documents * 8},
        }
        self.pipeline_runs[run_id] = run
        self.audit_records.insert(0, {
            "actor": payload.get("actor") or "knowledge-ops",
            "action": "ingest",
            "target_type": "pipeline_run",
            "target_id": run_id,
            "timestamp": now_iso(),
        })
        lineage = self.lineage.setdefault(knowledge_base_id, {
            "knowledge_base_id": knowledge_base_id,
            "source_documents": [],
            "artifacts": [],
            "consumers": [],
        })
        lineage["artifacts"].append(f"artifact-{knowledge_base_id}-{run_id}")
        return run

    def run_quality_check(self, payload):
        knowledge_base_id = payload.get("knowledge_base_id") or "kb-policy-docs"
        kb = self.knowledge_bases.get(knowledge_base_id)
        if not kb:
            return None
        run_id = f"run-quality-{self.next_run}"
        self.next_run += 1
        issues = []
        if kb["documents"] < 10:
            issues.append({"code": "LOW_DOCUMENT_COUNT", "message": "document count below recommended threshold"})
        if not kb["published"]:
            issues.append({"code": "NOT_PUBLISHED", "message": "knowledge base not published yet"})
        score = max(60, 100 - len(issues) * 12)
        report = {
            "knowledge_base_id": knowledge_base_id,
            "version": kb["version"],
            "score": score,
            "issues": issues,
            "generated_at": now_iso(),
        }
        self.quality_reports[knowledge_base_id] = report
        run = {
            "pipeline_run_id": run_id,
            "pipeline_type": "quality-check",
            "name": payload.get("name") or "manual-quality-check",
            "status": "success",
            "knowledge_base_id": knowledge_base_id,
            "started_at": now_iso(),
            "ended_at": now_iso(),
            "inputs": {"documents": kb["documents"]},
            "outputs": {"issues": len(issues), "score": score},
        }
        self.pipeline_runs[run_id] = run
        self.audit_records.insert(0, {
            "actor": payload.get("actor") or "knowledge-ops",
            "action": "quality-check",
            "target_type": "pipeline_run",
            "target_id": run_id,
            "timestamp": now_iso(),
        })
        return run

    def list_quality_reports(self):
        return list(self.quality_reports.values())

    def get_quality_report(self, knowledge_base_id):
        return self.quality_reports.get(knowledge_base_id)

    def list_audit_records(self, limit=20):
        return self.audit_records[:limit]

    def get_lineage(self, knowledge_base_id):
        return self.lineage.get(knowledge_base_id)

    def ops_overview(self):
        knowledge_bases = self.list_knowledge_bases()
        pipeline_runs = self.list_pipeline_runs()
        reports = self.list_quality_reports()
        healthy_runs = len([item for item in pipeline_runs if item["status"] == "success"])
        total_issues = sum(len(item.get("issues", [])) for item in reports)
        trusted_assets = len([item for item in reports if item.get("score", 0) >= 85])
        average_score = int(sum(item.get("score", 0) for item in reports) / max(len(reports), 1))
        return {
            "status": "UP",
            "service": "agent-knowledge-ops",
            "knowledge_bases": [
                {
                    "name": item["name"],
                    "knowledge_base_id": item["knowledge_base_id"],
                    "status": item["status"],
                    "documents": item["documents"],
                    "domain": item["domain"],
                    "version": item["version"],
                    "owner": item["owner"],
                    "published": item["published"],
                }
                for item in knowledge_bases
            ],
            "pipelines": [
                {
                    "name": item["name"],
                    "pipeline_run_id": item["pipeline_run_id"],
                    "pipeline_type": item["pipeline_type"],
                    "status": item["status"],
                    "knowledge_base_id": item["knowledge_base_id"],
                    "outputs": item["outputs"],
                }
                for item in pipeline_runs[-10:]
            ],
            "quality": {
                "trusted_assets": trusted_assets,
                "issues": total_issues,
                "average_score": average_score,
            },
            "summary": {
                "knowledge_base_count": len(knowledge_bases),
                "pipeline_run_count": len(pipeline_runs),
                "healthy_pipelines": healthy_runs,
            },
            "recent_audits": self.list_audit_records(),
        }


STORE = KnowledgeOpsStore()


class Handler(BaseHTTPRequestHandler):
    def _json(self, status_code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        body = self.rfile.read(length).decode("utf-8")
        return json.loads(body) if body else {}

    def log_message(self, format, *args):
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/health":
            self._json(200, {"status": "UP", "service": "agent-knowledge-ops"})
            return
        if path in ("/ops/knowledge", "/ops/overview"):
            self._json(200, STORE.ops_overview())
            return
        if path == "/knowledge-bases":
            self._json(200, {"status": "ok", "items": STORE.list_knowledge_bases()})
            return
        if path.startswith("/knowledge-bases/"):
            knowledge_base_id = path.split("/")[-1]
            item = STORE.get_knowledge_base(knowledge_base_id)
            if not item:
                self._json(404, {"status": "error", "message": "knowledge base not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/pipelines/runs":
            self._json(200, {"status": "ok", "items": STORE.list_pipeline_runs()})
            return
        if path.startswith("/pipelines/runs/"):
            run_id = path.split("/")[-1]
            item = STORE.get_pipeline_run(run_id)
            if not item:
                self._json(404, {"status": "error", "message": "pipeline run not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/quality/reports":
            self._json(200, {"status": "ok", "items": STORE.list_quality_reports()})
            return
        if path.startswith("/quality/reports/"):
            knowledge_base_id = path.split("/")[-1]
            item = STORE.get_quality_report(knowledge_base_id)
            if not item:
                self._json(404, {"status": "error", "message": "quality report not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/audit/records":
            self._json(200, {"status": "ok", "items": STORE.list_audit_records()})
            return
        if path.startswith("/lineage/"):
            knowledge_base_id = path.split("/")[-1]
            item = STORE.get_lineage(knowledge_base_id)
            if not item:
                self._json(404, {"status": "error", "message": "lineage not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        payload = self._read_json()
        if path == "/knowledge-bases":
            item = STORE.create_knowledge_base(payload)
            self._json(201, {"status": "ok", "item": item})
            return
        if path.startswith("/knowledge-bases/") and path.endswith("/publish"):
            knowledge_base_id = path.split("/")[-2]
            item = STORE.publish_knowledge_base(knowledge_base_id)
            if not item:
                self._json(404, {"status": "error", "message": "knowledge base not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/pipelines/ingest":
            run = STORE.run_ingest(payload)
            if not run:
                self._json(404, {"status": "error", "message": "knowledge base not found"})
                return
            self._json(201, {"status": "ok", "item": run})
            return
        if path == "/pipelines/quality-check":
            run = STORE.run_quality_check(payload)
            if not run:
                self._json(404, {"status": "error", "message": "knowledge base not found"})
                return
            self._json(201, {"status": "ok", "item": run})
            return
        self._json(404, {"status": "error", "message": "not found"})


def self_test():
    item = STORE.create_knowledge_base({"knowledge_base_id": "kb-self-test", "name": "self-test-kb", "documents": 3})
    assert item["status"] == "draft"
    ingest_run = STORE.run_ingest({"knowledge_base_id": "kb-self-test", "documents": 2, "actor": "tester"})
    assert ingest_run["pipeline_type"] == "ingest"
    quality_run = STORE.run_quality_check({"knowledge_base_id": "kb-self-test", "actor": "tester"})
    assert quality_run["pipeline_type"] == "quality-check"
    published = STORE.publish_knowledge_base("kb-self-test")
    assert published["published"] is True
    overview = STORE.ops_overview()
    assert overview["summary"]["knowledge_base_count"] >= 3
    assert "recent_audits" in overview


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        print("self-test ok")
        raise SystemExit(0)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-knowledge-ops listening on {PORT}")
    server.serve_forever()
