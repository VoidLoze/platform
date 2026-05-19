"""Изолированный запуск команд в Docker для AI-check.

Рабочая копия передаётся в именованный Docker volume (без bind-mount с /tmp внутри
контейнера раннера). Иначе на Docker Desktop for Mac демон отклоняет mount:
«path is not shared from the host» — путь существует только внутри контейнера раннера.
"""

import io
import os
import subprocess
import uuid
import zipfile

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

app = FastAPI(title="aicheck-runner", version="1.0")

RUNNER_TOKEN = os.environ.get("RUNNER_TOKEN", "").strip()
TIMEOUT_S = int(os.environ.get("RUNNER_TIMEOUT_S", "180"))
POPULATE_IMAGE = os.environ.get("RUNNER_POPULATE_IMAGE", "python:3.12-alpine")


def _verify(header_value: str | None) -> None:
    if not RUNNER_TOKEN:
        raise HTTPException(status_code=500, detail="RUNNER_TOKEN not configured")
    token = (header_value or "").strip()
    if token != RUNNER_TOKEN:
        raise HTTPException(status_code=401, detail="unauthorized")


def _docker_volume_rm(name: str) -> None:
    subprocess.run(
        ["docker", "volume", "rm", "-f", name],
        capture_output=True,
        text=True,
        timeout=60,
    )


def _populate_volume_from_zip(vol_name: str, blob: bytes) -> None:
    """Распаковывает zip в корень volume через контейнер; bind-mount только имени volume."""
    # ZipFile требует seekable поток; stdin при docker -i может быть не seekable —
    # читаем весь архив и открываем через BytesIO.
    script = (
        "import io,zipfile,sys\n"
        "blob=sys.stdin.buffer.read()\n"
        "zipfile.ZipFile(io.BytesIO(blob)).extractall('.')\n"
    )
    populate_cmd = [
        "docker",
        "run",
        "--rm",
        "-i",
        "-v",
        f"{vol_name}:/workspace",
        "-w",
        "/workspace",
        POPULATE_IMAGE.strip(),
        "python",
        "-c",
        script,
    ]
    proc = subprocess.run(
        populate_cmd,
        input=blob,
        capture_output=True,
        timeout=min(TIMEOUT_S, 120),
    )
    if proc.returncode != 0:
        err = ((proc.stderr or b"").decode(errors="replace") + (proc.stdout or b"").decode(errors="replace")).strip()
        raise HTTPException(
            status_code=500,
            detail=f"failed to unzip into docker volume: {err or proc.returncode}",
        )


@app.get("/health/live")
def health_live() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/run")
def run_workspace(
    workspace: UploadFile = File(...),
    image: str = Form(...),
    command: str = Form(...),
    cpus: str = Form("2"),
    memory: str = Form("1g"),
    stdin: str = Form(""),
    x_platform_token: str | None = Header(default=None, alias="X-Platform-Aicheck-Runner-Token"),
) -> dict[str, object]:
    """Принимает zip дерева файлов, кладёт в Docker volume и выполняет команду без host bind-mount."""
    _verify(x_platform_token)
    cpus_clean = cpus.strip() or "2"
    memory_clean = memory.strip() or "1g"
    blob = workspace.file.read()
    if len(blob) == 0:
        raise HTTPException(status_code=400, detail="empty workspace")

    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as zf:
            if len(zf.namelist()) == 0:
                raise HTTPException(status_code=400, detail="zip has no entries")
    except zipfile.BadZipFile as exc:
        raise HTTPException(status_code=400, detail=f"bad zip: {exc}") from exc

    vol_name = f"aicheck_ws_{uuid.uuid4().hex[:20]}"
    create = subprocess.run(
        ["docker", "volume", "create", vol_name],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if create.returncode != 0:
        raise HTTPException(status_code=500, detail=f"docker volume create failed: {(create.stderr or create.stdout).strip()}")

    try:
        _populate_volume_from_zip(vol_name, blob)

        stdin_clean = stdin if stdin else ""
        if len(stdin_clean) > 65536:
            raise HTTPException(status_code=400, detail="stdin exceeds 65536 chars")

        cmd = [
            "docker",
            "run",
            "--rm",
            "--cpus",
            cpus_clean,
            "-m",
            memory_clean,
            "--network",
            "none",
        ]
        feed_stdin = len(stdin_clean) > 0
        if feed_stdin:
            cmd.append("-i")
        cmd.extend(
            [
                "-v",
                f"{vol_name}:/workspace",
                "-w",
                "/workspace",
                image.strip(),
                "sh",
                "-lc",
                command.strip(),
            ]
        )
        stdout = stderr = ""
        code = -1
        status = "FAILED"
        try:
            proc = subprocess.run(
                cmd,
                input=stdin_clean if feed_stdin else None,
                capture_output=True,
                text=True,
                timeout=TIMEOUT_S,
            )
            stdout = proc.stdout or ""
            stderr = proc.stderr or ""
            code = proc.returncode
            status = "PASSED" if code == 0 else "FAILED"
        except subprocess.TimeoutExpired as te:
            so, se = te.stdout, te.stderr
            if isinstance(so, bytes):
                stdout = so.decode(errors="replace")
            else:
                stdout = so or ""
            if isinstance(se, bytes):
                stderr = se.decode(errors="replace")
            else:
                stderr = se or ""
            code = -1
            status = "TIMEOUT"
        except FileNotFoundError as exc:
            raise HTTPException(status_code=500, detail="docker cli not found in runner image") from exc

        combined = (stdout + "\n" + stderr).strip()
        if len(combined) > 120_000:
            combined = combined[:120_000] + "\n...truncated..."

        return {"exitCode": code, "output": combined or "(no output)", "status": status}
    finally:
        _docker_volume_rm(vol_name)

