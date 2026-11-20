"""Render DepGuard's AWS architecture diagram with service icons (depguard-architecture-aws.svg/.png).

Same drawing style as the payroll-ai-strands and RAG_Hybrid_Search diagrams.
Three stories: scanning a pom.xml, deploying, and what later phases add.

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
W, H = 1600, 760

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
text(W / 2, 84, "A pom.xml goes in; vulnerable dependencies ranked by severity, with the version to upgrade to, come out.",
     size=17, color=GREY)

# ------------------------------------------------------------------ story 1: scanning a pom.xml
panel(30, 110, 1540, 400, "①  Scanning a pom.xml  —  about 2 seconds", "#F8FBFF", BLUE)

icon("users", 85, 320, ["Developer", "curl · IntelliJ"], size=72)
arrow("M125 310 L 247 310", BLUE, "pom.xml", (186, 298))
arrow("M247 332 L 125 332", BLUE, "report", (186, 352))
icon("api-gateway", 285, 320, ["Amazon API Gateway", "HTTP API · HTTPS", "throttled 5 req/s"], size=60)
arrow("M320 320 L 376 320", BLUE)

# Lambda: Spring Boot runs inside, unchanged
panel(380, 150, 800, 340, "", "#FFF4E6", ORANGE)
image("lambda", 398, 164, 42)
text(452, 182, "AWS Lambda  ·  Java 21  ·  ARM64  ·  SnapStart", size=17, color=ORANGE, weight="bold", anchor="start")
text(452, 203, "alias “live” → newest version  ·  Spring Boot runs inside, unchanged", size=13.5, anchor="start")
image("spring", 1128, 164, 36)

step(400, 232, 240, 80, "StreamLambdaHandler", ["API Gateway event →", "servlet request"], ORANGE, "#FFFFFF")
arrow("M640 272 L 666 272", ORANGE)
step(670, 232, 240, 80, "ScanController", ["POST /api/scans", "size limit · errors as JSON"], ORANGE, "#FFFFFF")
arrow("M910 272 L 936 272", ORANGE)
step(940, 232, 220, 80, "ScanService", ["ranks by severity,", "picks the safe upgrade"], ORANGE, "#FFFFFF")

step(670, 372, 240, 80, "PomParser", ["safe XML (blocks XXE),", "resolves ${versions}"], ORANGE, "#FFFFFF")
step(940, 372, 220, 80, "OsvClient", ["1 batch query, then", "parallel fetches"], ORANGE, "#FFFFFF")
arrow("M1000 312 L 1000 342 L 790 342 L 790 368", ORANGE)
arrow("M1100 312 L 1100 368", ORANGE)
text(405, 395, ["Locally: ./mvnw spring-boot:run", "serves the same controller", "on Tomcat :8080 (no Lambda)."],
     size=13.5, color=GREY, anchor="start")

# Right column: logs and the open data source
arrow("M1180 252 L 1306 252", GREY, "logs", (1243, 240), dashed=True)
icon("cloudwatch", 1350, 252, ["Amazon CloudWatch", "logs · 7-day retention"], size=60)
arrow("M1160 412 L 1308 412", GREEN, "HTTPS", (1234, 400))
badge(1350, 412, "OSV", ["OSV.dev API", "live advisory data, free"], GREEN, "#EBFBEE")

# ------------------------------------------------------------------ story 2: deploying
panel(30, 530, 760, 210, "②  Deploying  —  scripts/deploy.sh, about 3–5 minutes", "#F8F9FA", GREY)
icon("apachemaven", 120, 625, ["./mvnw -Plambda package", "tests + Lambda zip"], size=50, label_size=14)
arrow("M210 625 L 266 625", GREY)
icon("terraform", 320, 625, ["Terraform", "infra/serverless"], size=50, label_size=14)
arrow("M370 625 L 446 625", GREY)
icon("lambda", 500, 625, ["New Lambda version", "SnapStart snapshot"], size=50, label_size=14)
arrow("M555 625 L 596 625", GREY)
step(600, 590, 170, 72, "Health check", ["GET /actuator/health", "prints the URL"], GREY, "#FFFFFF")

# ------------------------------------------------------------------ story 3: later phases
panel(810, 530, 760, 210, "③  Coming in later phases", "#FBFAFF", PURPLE, dashed=True)
icon("bedrock", 905, 625, ["Amazon Bedrock", "phase 2 · AI agent"], size=50, label_size=14)
icon("aurora", 1095, 625, ["Aurora Serverless v2", "phase 3 · pgvector"], size=50, label_size=14)
icon("langfuse", 1285, 625, ["Langfuse", "phase 5 · LLM traces"], size=50, label_size=14)
icon("springsecurity", 1475, 625, ["Spring Security", "phase 8 · JWT + roles"], size=46, label_size=14)

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
