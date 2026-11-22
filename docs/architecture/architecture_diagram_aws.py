"""Render DepGuard's AWS architecture diagram with service icons (depguard-architecture-aws.svg/.png).

Same drawing style as the payroll-ai-strands and RAG_Hybrid_Search diagrams.
Three stories: scanning and triaging a pom.xml (with the RAG knowledge base), deploying, and what later phases add.

    python3 docs/architecture/architecture_diagram_aws.py    # the PNG needs rsvg-convert (brew install librsvg)

Logos (in logos/):
  AWS service icons (API Gateway, Lambda, CloudWatch, Bedrock, Aurora): AWS Architecture Icons,
    taken from the `diagrams` package's AWS set; AWS allows them in architecture diagrams.
  Spring, Spring Security, Apache Maven: Simple Icons (single-colour SVGs, coloured here with the brand colour).
  Terraform, users: copied from payroll-ai-strands. Langfuse: the project's own icon.
OSV.dev has no logo in these sets, so it is drawn as a labelled badge.
"""
import base64
import shutil
import subprocess
from html import escape
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "depguard-architecture-aws"
W, H = 1600, 950

BLUE, PURPLE, GREEN, GREY, INK, ORANGE = "#1C7ED6", "#7048E8", "#2B8A3E", "#495057", "#212529", "#D9480F"
FONT = "Helvetica Neue, Helvetica, Arial, sans-serif"
# Simple Icons logos are single-colour SVGs; these are the brand colours.
BRAND = {"spring": "#6DB33F", "springsecurity": "#6DB33F", "apachemaven": "#C71A36"}
parts: list[str] = []


def logo(name: str) -> str:
    svg = HERE / "logos" / f"{name}.svg"
    if svg.exists():
        coloured = svg.read_text().replace("<svg ", f'<svg fill="{BRAND[name]}" ', 1)
        return "data:image/svg+xml;base64," + base64.b64encode(coloured.encode()).decode()
    return "data:image/png;base64," + base64.b64encode((HERE / "logos" / f"{name}.png").read_bytes()).decode()


def text(x, y, lines, size=15, color=INK, weight="normal", anchor="middle"):
    lines = [lines] if isinstance(lines, str) else lines
    spans = "".join(f'<tspan x="{x}" dy="{0 if i == 0 else size + 4}">{escape(ln)}</tspan>'
                    for i, ln in enumerate(lines))
    parts.append(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" fill="{color}" '
                 f'font-weight="{weight}" text-anchor="{anchor}">{spans}</text>')


def panel(x, y, w, h, title, fill, stroke, size=18, dashed=False):
    dash = ' stroke-dasharray="10 7"' if dashed else ""
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="{fill}" '
                 f'stroke="{stroke}" stroke-width="2.5"{dash}/>')
    if title:
        text(x + 20, y + 32, title, size=size, color=stroke, weight="bold", anchor="start")


def image(name, x, y, size):
    parts.append(f'<image href="{logo(name)}" x="{x}" y="{y}" width="{size}" height="{size}"/>')


def icon(name, cx, cy, label, size=64, label_size=15):
    image(name, cx - size / 2, cy - size / 2, size)
    text(cx, cy + size / 2 + 22, label, size=label_size)


def badge(cx, cy, word, label, color, fill, r=32):
    """A round labelled badge, for services that have no logo in these icon sets."""
    parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" stroke="{color}" stroke-width="2.5"/>')
    text(cx, cy + 6, word, size=17, color=color, weight="bold")
    text(cx, cy + r + 22, label)


def step(x, y, w, h, title, sub, color, fill):
    """A plain-language step: bold name + one or two short lines of what it does."""
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{fill}" '
                 f'stroke="{color}" stroke-width="2.2"/>')
    sub = [sub] if isinstance(sub, str) else sub
    top = y + h / 2 - (len(sub) * 18) / 2 + 2
    text(x + w / 2, top, title, size=17, color=color, weight="bold")
    text(x + w / 2, top + 22, sub, size=13.5, color=INK)


def arrow(d, color, label=None, at=None, anchor="middle", dashed=False):
    dash = ' stroke-dasharray="8 6"' if dashed else ""
    parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2.8"{dash} '
                 f'marker-end="url(#a-{color[1:]})"/>')
    if label:
        parts.append(f'<text x="{at[0]}" y="{at[1]}" font-family="{FONT}" font-size="14" '
                     f'font-weight="bold" text-anchor="{anchor}" stroke="white" stroke-width="6" '
                     f'stroke-linejoin="round" paint-order="stroke" fill="{color}">{escape(label)}</text>')


# ------------------------------------------------------------------ title
text(W / 2, 50, "DepGuard  ·  Java dependency vulnerability triage, serverless on AWS", size=30, weight="bold")
text(W / 2, 84, "A pom.xml goes in; vulnerable dependencies ranked by risk, safe upgrade versions and an AI triage of what to fix first come out.",
     size=17, color=GREY)

# ------------------------------------------------------------------ story 1: scanning and triaging a pom.xml
panel(30, 110, 1540, 590, "①  Scanning and triaging a pom.xml  —  a scan ~2 s, an AI triage ~2.6 s (cached: ~10 ms)",
      "#F8FBFF", BLUE)

icon("users", 85, 320, ["Developer", "curl · IntelliJ"], size=72)
arrow("M125 310 L 247 310", BLUE, "pom.xml", (186, 298))
arrow("M247 332 L 125 332", BLUE, "report", (186, 352))
icon("api-gateway", 285, 320, ["Amazon API Gateway", "HTTP API · HTTPS", "throttled 5 req/s"], size=60)
arrow("M320 320 L 376 320", BLUE)

# Lambda: Spring Boot runs inside, unchanged
panel(380, 150, 800, 430, "", "#FFF4E6", ORANGE)
image("lambda", 398, 164, 42)
text(452, 182, "AWS Lambda  ·  Java 21  ·  ARM64  ·  SnapStart", size=17, color=ORANGE, weight="bold", anchor="start")
text(452, 203, "alias “live” → newest version  ·  Spring Boot runs inside, unchanged", size=13.5, anchor="start")
image("spring", 1128, 164, 36)

# Row 1: request handling and the deterministic scan
step(400, 232, 240, 80, "StreamLambdaHandler", ["API Gateway event →", "servlet request"], ORANGE, "#FFFFFF")
arrow("M640 272 L 666 272", ORANGE)
step(670, 232, 240, 80, "Controllers", ["POST /api/scans", "POST /api/triage (API key)"], ORANGE, "#FFFFFF")
arrow("M910 272 L 936 272", ORANGE)
step(940, 232, 220, 80, "ScanService", ["parses the pom (no XXE),", "ranks, picks upgrades"], ORANGE, "#FFFFFF")

# Row 2: the agent, and the advisory client
step(670, 352, 240, 80, "TriageAgent", ["facts in code · answer cache", "1 model tool · citation check"], PURPLE, "#F3F0FF")
step(940, 352, 220, 80, "OsvClient", ["1 batch query, then", "parallel fetches"], ORANGE, "#FFFFFF")
arrow("M790 312 L 790 348", ORANGE)
arrow("M1050 312 L 1050 348", ORANGE)
text(405, 345, ["Locally: ./mvnw spring-boot:run", "serves the same controllers on", "Tomcat :8080; Postgres in Docker."],
     size=13.5, color=GREY, anchor="start")

# Row 3: exploit-signal clients, used only by the agent's tools
step(940, 472, 220, 80, "EpssClient · KevClient", ["batched lookup,", "KEV feed cached 6 h"], ORANGE, "#FFFFFF")
arrow("M910 372 L 925 372 L 925 292 L 936 292", PURPLE)   # tool: findVulnerabilities -> the scan
arrow("M910 412 L 925 412 L 925 512 L 936 512", PURPLE)   # tools: EPSS + KEV
text(780, 458, ["code: OSV, EPSS,", "KEV (ranked)", "model tool:", "searchAdvisories"],
     size=12.5, color=PURPLE, anchor="end")

# Row 3, left: the RAG retriever (tool: searchAdvisories)
step(400, 472, 240, 80, "HybridRetriever", ["Titan query embedding,", "pgvector + full-text, RRF"], PURPLE, "#F3F0FF")
arrow("M670 420 L 520 420 L 520 468", PURPLE)

# The model and the knowledge base, below the Lambda
arrow("M790 432 L 790 612", PURPLE, "Converse API", (780, 600), anchor="end")
image("bedrock", 765, 615, 50)
text(830, 636, "Amazon Bedrock", size=16, color=PURPLE, weight="bold", anchor="start")
text(830, 656, "Claude Haiku 4.5  ·  Titan v2 (embeddings)  ·  IAM role", size=13.5, anchor="start")
arrow("M520 552 L 520 612", PURPLE, "RDS Data API", (510, 600), anchor="end")
image("aurora", 495, 615, 50)
text(555, 636, "Aurora Serverless v2", size=16, color=PURPLE, weight="bold", anchor="start")
text(555, 656, "pgvector · answer cache", size=13.5, anchor="start")

# Right column: logs and the open data sources
arrow("M1180 252 L 1351 252", GREY, "logs", (1265, 240), dashed=True)
icon("cloudwatch", 1385, 252, ["Amazon CloudWatch", "logs · 7-day retention"], size=60)
arrow("M1160 392 L 1266 392", GREEN, "HTTPS", (1213, 380))
step(1270, 352, 230, 80, "OSV.dev API", ["advisories (incl. GitHub", "Advisory Database), free"], GREEN, "#EBFBEE")
arrow("M1160 512 L 1266 512", GREEN, "HTTPS", (1213, 500))
step(1270, 472, 230, 80, "FIRST EPSS · CISA KEV", ["exploit probability ·", "known exploited, free"], GREEN, "#EBFBEE")

# Traces: every triage as a span tree (model calls, tool calls, retrieval) with tokens, cost and eval scores
arrow("M1180 562 L 1290 562 L 1290 600", GREY, "OTLP traces", (1222, 584), dashed=True)
image("langfuse", 1265, 605, 50)
text(1325, 626, "Langfuse Cloud", size=16, color=GREY, weight="bold", anchor="start")
text(1325, 646, "traces · cost · eval scores", size=13.5, anchor="start")

# ------------------------------------------------------------------ story 2: deploying
panel(30, 720, 760, 210, "②  Deploying  —  scripts/deploy.sh, about 3–5 minutes", "#F8F9FA", GREY)
icon("apachemaven", 120, 815, ["./mvnw -Plambda package", "tests + Lambda zip"], size=50, label_size=14)
arrow("M210 815 L 266 815", GREY)
icon("terraform", 320, 815, ["Terraform", "zip → S3 → Lambda"], size=50, label_size=14)
arrow("M370 815 L 446 815", GREY)
icon("lambda", 500, 815, ["New Lambda version", "SnapStart snapshot"], size=50, label_size=14)
arrow("M555 815 L 596 815", GREY)
step(600, 780, 170, 72, "Health check", ["GET /actuator/health", "prints URL + key cmd"], GREY, "#FFFFFF")
text(50, 900, "Then once (and to refresh): scripts/ingest.sh aws  —  OSV export + migration guides → Titan embeddings → Aurora",
     size=13.5, color=GREY, anchor="start")
text(50, 918, "Quality: ./mvnw test -Pevals  —  golden sets, code checks + an Amazon Nova Pro judge",
     size=13.5, color=GREY, anchor="start")

# ------------------------------------------------------------------ story 3: later phases
panel(810, 720, 760, 210, "③  Coming in later phases", "#FBFAFF", PURPLE, dashed=True)
badge(940, 805, "8", ["Secrets Manager, alarms", "phase 8 · production hardening"], PURPLE, "#F3F0FF")
badge(1190, 805, "8", ["Human-approved upgrade PRs", "phase 8 · GitHub"], PURPLE, "#F3F0FF")
icon("springsecurity", 1440, 815, ["Spring Security", "phase 8 · JWT + roles"], size=46, label_size=14)

# ------------------------------------------------------------------ write
markers = "".join(
    f'<marker id="a-{c[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" '
    f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>'
    for c in (BLUE, PURPLE, GREEN, GREY, ORANGE))
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
       f'<title>DepGuard architecture on AWS</title>'
       f'<defs>{markers}</defs><rect width="{W}" height="{H}" fill="white"/>' + "".join(parts) + "</svg>")
OUT.with_suffix(".svg").write_text(svg)
print(f"wrote {OUT.with_suffix('.svg').name}")
if shutil.which("rsvg-convert"):
    subprocess.run(["rsvg-convert", "-z", "2", "-o", str(OUT.with_suffix(".png")),
                    str(OUT.with_suffix(".svg"))], check=True)
    print(f"wrote {OUT.with_suffix('.png').name} ({W * 2}x{H * 2})")
