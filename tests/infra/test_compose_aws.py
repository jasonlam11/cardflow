"""
The AWS compose override (docker-compose.aws.yml) is the last line of defence behind the
security group: these tests render the merged config with dummy values and check that only
the dashboard is published and that every service image comes from ECR. Needs only the
Docker CLI (no daemon, no AWS).
"""

import json
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
OUR_IMAGES = {"ledger-service", "authorization-service", "fraud-service", "assistant-service", "dashboard", "simulator"}
DUMMY_ENV = {
    "POSTGRES_ADMIN_USER": "admin", "POSTGRES_ADMIN_PASSWORD": "x", "LEDGER_DB_PASSWORD": "x",
    "AUTHORIZATION_DB_PASSWORD": "x", "ASSISTANT_DB_PASSWORD": "x", "FRAUD_DB_PASSWORD": "x",
    "ADMIN_API_KEY": "x", "ECR_REGISTRY": "123456789012.dkr.ecr.us-east-1.amazonaws.com",
    "IMAGE_TAG": "0123abc", "BEDROCK_MODEL_ID": "global.anthropic.claude-haiku-4-5-20251001-v1:0",
    "AWS_REGION": "us-east-1",
}


def render(tmp_path: Path, env: dict) -> dict:
    env_file = tmp_path / "aws.env"
    env_file.write_text("".join(f"{k}={v}\n" for k, v in env.items()))
    out = subprocess.run(
        ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.aws.yml",
         "--env-file", str(env_file), "--profile", "sim", "config", "--format", "json"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise AssertionError(out.stderr)
    return json.loads(out.stdout)["services"]


@pytest.fixture
def services(tmp_path):
    return render(tmp_path, DUMMY_ENV)


def test_only_the_dashboard_publishes_a_port(services):
    published = {name: s["ports"] for name, s in services.items() if s.get("ports")}
    assert set(published) == {"dashboard"}
    assert [(p["target"], p["published"]) for p in published["dashboard"]] == [(3000, "3000")]


def test_every_service_image_comes_from_ecr_at_the_commit(services):
    for name in OUR_IMAGES:
        assert services[name]["image"] == f"{DUMMY_ENV['ECR_REGISTRY']}/cardflow/{name}:0123abc"
        assert "build" not in services[name], f"{name} must not be built on the instance"


def test_logs_are_size_capped_everywhere(services):
    for name, s in services.items():
        assert s["logging"]["options"]["max-size"] == "10m", name


def test_assistant_uses_bedrock_with_docs_from_s3_and_no_api_key(services):
    a = services["assistant-service"]
    assert a["environment"]["ASSISTANT_PROVIDER"] == "bedrock"
    assert a["environment"]["ANTHROPIC_API_KEY"] == ""
    assert a["environment"]["DOCS_DIR"] == "/app/docs-s3"
    assert {(v["source"], v["target"], v["read_only"]) for v in a["volumes"]} == {("/opt/cardflow/docs", "/app/docs-s3", True)}


def test_missing_image_tag_fails_loudly(tmp_path):
    env = {k: v for k, v in DUMMY_ENV.items() if k != "IMAGE_TAG"}
    with pytest.raises(AssertionError, match="IMAGE_TAG"):
        render(tmp_path, env)
