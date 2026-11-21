#!/usr/bin/env python3
"""
개발용 가짜 Lucia 접속 API 서버(docs/app-api.md 프로토콜 판 2, 이모지·스티커는 docs/app-expressions.md).

운영 데몬(luciad)에 토큰을 발급하지 않고 앱을 시험하기 위한 것이다. 사양서의 HTTP·WebSocket 동작을 메모리에서 흉내 낸다.
  - 토큰은 고정이다(아래 TOKEN). 실제 토큰이 아니다.
  - 말에 특정 낱말을 넣으면 시나리오가 돈다(한글/영어): 카드/card, 파일/file, 이미지/image, 미리보기/preview(앱 안에서 보이는 파일과
    보일 수 없는 파일 한 벌: 표가 든 마크다운, 코드, CP949 CSV, 큰 로그, 줄글, 한 줄짜리 JSON, 긴 그림, 잔글씨가 든 긴 갈무리,
    여러 쪽 PDF, 깨진 글·그림·PDF, 압축 파일, 410으로 답하는 파일), 긴글/long, 알림/note, 끊어/dropme, 취소/revoke, 코드/code,
    이모지/emoji(글 속 `:name:`: 목록에 있는 것, 없는 것, `\\:`로 막은 것, 코드 안의 것),
    스티커/sticker(글 뒤에 `sticker` 프레임 한 장),
    로그인/login(입력 칸이 있는 로그인 카드, 결정 0063. 넣는 아이디가 결과를 정한다: ok, already, code, captcha, 그 밖은 실패해
    새 카드가 3번까지 온다. 넣은 값은 찍지 않고 칸 이름과 길이만 남긴다). `--no-forms`를 주면 form을 모르는 예전 데몬처럼 군다.
  - `--scopes browser_handover`를 주면 브라우저 화면 넘겨받기(6.7, 8.9)가 생긴다: 눈금이 그려진 1365×812 화면에 사람 확인 칸
    하나(520,360~580,420)와 글 칸이 있고, 칸을 누르면 초록으로 바뀐다. /_dev/handover 로 누른 자리·넣은 글의 길이·키·시작과 끝의
    횟수를 보고, ?expire=1(시간이 다 됨), ?popup=1(새 탭)로 조작한다. 화면은 Pillow가 있으면 JPEG, 없으면 PNG다.
  - 이모지·스티커 목록은 `expressions/` 폴더의 견본 그림으로 만든다(아래 EXPRESSIONS). `--no-expressions`를 주면 이 기능이 없는
    데몬처럼 군다(`welcome.expressions` 없음, `GET /v1/expressions`는 501).
  - `--scopes security_mode`를 주면 이 토큰이 권한 모드를 바꿀 수 있다(기본은 범위 없음: 보기만 한다).
  - /_dev/... 는 시험용 조작 입구다(사양에 없다): /_dev/send?kind=text&body=..., /_dev/send?kind=sticker&name=축하,
    /_dev/drop, /_dev/reset(`?empty=1`이면 기록이 없는 처음 상태), /_dev/expressions?remove=<이름>(목록에서 빼기)·?restore=1(되돌리기),
    /_dev/slow?ms=3000(파일을 그 시간에 걸쳐 천천히 보낸다, 0이면 끈다), /_dev/fail?n=1(다음 내려받기 n번을 503으로 답한다),
    /_dev/compact?ms=5000&outcome=small&error=failed&busy=2&done=0(대화 정리 `session.compact`가 걸리는 시간·결과·오류·busy 횟수),
    /_dev/model?set=opus(다른 곳에서 주 모델을 바꾼 것처럼), /_dev/state(번호, 연결, 푸시 등록을 본다).
  - 명령은 `권한`과 `모델` 둘이다(`commands`의 `current` 포함). `--legacy-commands`를 주면 `모델`·`current`·`session.compact`가 없는
    예전 데몬처럼 군다.
  - 앱이 내준 기능(`hello.tools`, 사양 7.2)은 데몬처럼 정리해 `welcome.tools`로 돌려준다. `--scopes app_tools`를 주면 루시아가
    부를 수 있다: 말에 위치/where(location.get), 받은 알림/notices(notifications.recent)를 넣거나
    /_dev/invoke?method=location.get&params={"precise":true} 로 부른다(40초 기다림, 늦은 답에는 bad_ref). 결과의 글은 로그에 남기지
    않는다(휴대폰의 비공개 자료다).

  - 루시아의 프로필 그림(사양 8.7, 8.8): `welcome`·`/v1/me`의 `profile`, `GET /v1/profile`, `GET /v1/profile/avatar/{id}`.
    처음에는 정한 그림이 없다(`avatar: null`). 말에 프로필/avatar를 넣으면 루시아가 그림을 바꾸고(견본 둘을 번갈아), "프로필 없애"/noavatar를
    넣으면 없앤다. /_dev/profile?set=1|2|big|photo|wide|bad|huge|gone|none 으로도 바꾼다(big은 2048px, photo는 사진처럼 무거운 2 MB쯤의
    1254px, wide는 정사각형이 아닌 것,
    bad는 그림이 아닌 것, huge는 3 MiB를 넘는 것, gone은 그림이 404인 것. `&quiet=1`이면 프레임을 보내지 않아 다음 `welcome`에서
    알게 된다). 그림 받기도 /_dev/slow·/_dev/fail을 따른다. `--no-profile`을 주면 이 기능이 없는 예전 데몬처럼 군다
    (`profile` 필드 없음, 두 주소는 501).

실행:  python3 server.py [--port 8790] [--scopes security_mode,app_tools] [--no-expressions] [--legacy-commands] [--no-profile]
"""
import argparse, asyncio, hashlib, io, json, os, re, struct, time, uuid, zipfile, zlib
from aiohttp import web, WSMsgType

TOKEN = "lucia_" + "a1" * 32
CLIENT = "cl-3f9a0c12de"
CONV = f"app:{CLIENT}"
state = {}
SCOPES = []
FORMS_ON = True
# 브라우저 화면 넘겨받기(6.7)의 상태와, 앱이 보낸 입력의 기록. 넣은 글은 길이만 남긴다.
HAND = {"active": False, "until": 0.0, "checked": False, "clicks": [], "text_lens": [], "keys": [], "scroll": 0, "starts": 0, "ends": 0,
        "frames": 0, "popup": False}
HAND_MAX = 900
HAND_SIZE = (1365, 812)
HAND_BOX = (520, 360, 580, 420)  # 사람 확인 칸(왼쪽, 위, 오른쪽, 아래)
HAND_METHODS = ["browser.handover.end", "browser.handover.input", "browser.handover.start", "browser.handover.state"]
# 마지막 `press.inputs`의 모양(칸 이름과 길이). 값은 남기지 않는다.
LAST_INPUTS = {}
SLOW = {"ms": 0}
FAIL = {"n": 0}
MODES = {"엄격": "모든 도구를 승인 카드로 확인해요.", "기본": "위험한 도구는 승인 카드를 거쳐요.", "해제": "승인 없이 바로 실행해요."}
MODE_ALIASES = {"strict": "엄격", "standard": "기본", "open": "해제"}

# ---------------------------------------------------------------- 이모지·스티커(docs/app-expressions.md)

HERE = os.path.dirname(os.path.abspath(__file__))
EXPRESSIONS_ON = True
# 주 모델(app-api-changes.md 2026-11-20, 결정 0055). 글은 데몬의 `models.rs`·`approvals.rs`에서 옮겼다.
MODELS = [("fable", "Fable", "가장 유능합니다. 어려운 판단과 긴 작업에 맞고, 사용량을 가장 많이 씁니다."),
          ("opus", "Opus", "복잡한 일에 맞습니다. Sonnet보다 사용량을 더 씁니다."),
          ("sonnet", "Sonnet", "빠르고 가볍습니다. 일상 대화와 보통 작업에 맞습니다."),
          ("haiku", "Haiku", "가장 빠르고 가볍습니다. 복잡한 일에는 덜 맞습니다.")]
MODEL_NOTE = "바꾸면 다음 실행부터 적용됩니다. 대화는 이어지고, 바꾼 뒤 첫 실행은 새 모델이 지금까지의 대화를 처음부터 다시 읽어 평소보다 느리고 사용량이 더 듭니다."
# 예전 데몬 흉내(--legacy-commands): `모델` 명령, `current` 필드, `session.compact`가 없다.
LEGACY_COMMANDS = False
# 대화 정리(`session.compact`, 결정 0056)의 시험용 조작: 걸리는 시간, 결과, 오류, 처음 몇 번을 busy로 답할지.
COMPACT = {"ms": 3000, "outcome": "compacted", "error": None, "busy": 0, "running": False, "done": False}
CATEGORIES = [{"id": "lucia", "label": "루시아", "order": 0, "icon": "e_000000000001"},
              {"id": "mark", "label": "표시", "order": 1, "icon": None}]
# (종류, id, 이름, 보이는 이름, 설명, 태그, 분류, 파일)
EXPRESSIONS = [
    ("emoji", "e_000000000001", "lucia_smile", "루시아 웃음", "기분 좋게 웃는 얼굴", ["웃음", "좋아", "기쁨"], "lucia", "lucia_smile.png"),
    ("emoji", "e_000000000002", "lucia_wow", "루시아 놀람", "눈이 동그래진 얼굴", ["놀람", "우와"], "lucia", "lucia_wow.png"),
    ("emoji", "e_000000000003", "spark", "반짝", "금빛 별", ["별", "반짝"], "mark", "spark.png"),
    ("emoji", "e_000000000004", "ok_check", "", "확인 표시", ["확인", "완료"], "mark", "ok_check.png"),
    ("emoji", "e_000000000005", "moon_night", "달", "", ["밤", "달"], "", "moon_night.png"),
    ("sticker", "s_000000000001", "축하", "", "별이 도는 축하", ["축하", "합격"], "lucia", "party.webp"),
    ("sticker", "s_000000000002", "꾸벅", "", "", ["인사", "고마워"], "lucia", "bow.png"),
    ("sticker", "s_000000000003", "잘자", "", "달과 별", ["밤", "인사"], "", "goodnight.png"),
    ("sticker", "s_000000000004", "축하 GIF", "", "같은 그림의 GIF", ["축하"], "", "party.gif"),
]
MIMES = {".png": "image/png", ".webp": "image/webp", ".gif": "image/gif"}
expr = {"files": {}, "removed": set()}
# 앱이 내준 기능(결정 0053). 연결마다 새로 정한다.
TOOLS = {"list": [], "features": [], "pending": {}}
APP_CALL_TIMEOUT = 40
SEGMENT = re.compile(r"^[a-z0-9_-]{1,40}$")


def clean_features(names):
    out = sorted({n.strip().lower() for n in names if isinstance(n, str) and SEGMENT.match(n.strip().lower())})
    return out[:32]


def clean_tools(items):
    """데몬의 clean_tools(gateway.rs)와 같은 규칙: 이름은 마디 둘이나 셋, 설명은 한 줄 200자, 인자 설명은 2000바이트."""
    out, seen = [], set()
    for t in items if isinstance(items, list) else []:
        if not isinstance(t, dict):
            continue
        name = str(t.get("name") or "").strip()
        parts = name.split(".")
        if not (2 <= len(parts) <= 3) or not all(SEGMENT.match(x) for x in parts) or name in seen:
            continue
        params = t.get("params")
        if params is not None and len(json.dumps(params, ensure_ascii=False).encode()) > 2000:
            params = None
        desc = " ".join(str(t.get("description") or "").split())[:200]
        seen.add(name)
        out.append({"name": name, "description": desc, "params": params, "write": t.get("effect") == "write"})
        if len(out) >= 32:
            break
    return out


# ---------------------------------------------------------------- 프로필 그림(사양 8.7, 8.8, 결정 0060)

PROFILE_ON = True
PROFILE = {"avatar": None, "files": {}}
AVATAR_MAX = 3 * 1024 * 1024


def chunk(t, d):
    """PNG의 덩어리 하나(길이, 종류, 내용, CRC)."""
    c = struct.pack(">I", len(d)) + t + d
    return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)


def face_png(side, sky, skin, eye=(40, 52, 80), grain=0):
    """견본 얼굴: 위에서 아래로 옅어지는 바탕, 가운데의 둥근 얼굴, 눈 둘. 줄마다 구간을 칠해 큰 그림도 빨리 만든다.
    grain을 주면 아래 비트에 잡음을 섞어 사진처럼 잘 줄지 않는 파일이 된다(파일 크기를 키운다)."""
    def disc(row, y, cx, cy, r, color):
        dy = y - cy
        if abs(dy) >= r: return
        half = int((r * r - dy * dy) ** 0.5)
        x0, x1 = max(0, cx - half), min(side, cx + half)
        row[x0 * 3:x1 * 3] = bytes(color) * (x1 - x0)
    rows = bytearray()
    for y in range(side):
        fade = y * 50 // side
        row = bytearray(bytes(min(255, c + fade) for c in sky) * side)
        disc(row, y, side // 2, side * 11 // 20, side * 3 // 10, skin)
        disc(row, y, side * 2 // 5, side // 2, side // 24, eye)
        disc(row, y, side * 3 // 5, side // 2, side // 24, eye)
        if grain:
            row = bytes((v & ~grain) | (n & grain) for v, n in zip(row, os.urandom(len(row))))
        rows += b"\x00" + row
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b"")


def avatar_bytes(kind):
    if kind == "1": return face_png(512, (120, 170, 235), (255, 238, 225)), 512, 512
    if kind == "2": return face_png(800, (235, 140, 170), (255, 246, 214)), 800, 800
    if kind == "big": return face_png(2048, (120, 200, 160), (255, 240, 220)), 2048, 2048
    if kind == "photo": return face_png(1254, (150, 130, 220), (255, 236, 226), grain=0x07), 1254, 1254
    if kind == "wide": return png(600, 300), 600, 300
    if kind == "bad": return b"not an image " * 40, 256, 256
    if kind == "huge": return b"\x89PNG\r\n\x1a\n" + os.urandom(AVATAR_MAX + 4096), 1024, 1024
    return None


def set_avatar(kind, quiet=False):
    """프로필 그림을 정하거나(견본 이름) 없앤다(`none`). 바뀌면 연결된 앱에 번호 없는 `profile` 프레임을 보낸다(5.5)."""
    made = avatar_bytes(kind)
    if kind == "gone":
        # 정했다고 알리지만 그림은 이미 지워진 것(404).
        PROFILE["avatar"] = {"id": "a_" + "0" * 24, "mime": "image/png", "width": 512, "height": 512, "size": 1, "updated_at": iso_z(time.time())}
    elif made is None:
        PROFILE["avatar"] = None
    else:
        data, w, h = made
        aid = "a_" + hashlib.sha256(data).hexdigest()[:24]
        PROFILE["files"][aid] = data
        PROFILE["avatar"] = {"id": aid, "mime": "image/png", "width": w, "height": h, "size": len(data), "updated_at": iso_z(time.time())}
    print(f"[profile] avatar={PROFILE['avatar'] and PROFILE['avatar']['id']} quiet={quiet}")
    if PROFILE_ON and not quiet:
        push_frame({"type": "profile", "avatar": PROFILE["avatar"]}, numbered=False)
    return PROFILE["avatar"]


def iso_z(t):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


def load_expressions():
    for kind, eid, name, label, desc, tags, cat, fname in EXPRESSIONS:
        with open(os.path.join(HERE, "expressions", fname), "rb") as f:
            data = f.read()
        fid = "x_" + hashlib.sha256(data).hexdigest()[:24]
        expr["files"][fid] = (data, MIMES[os.path.splitext(fname)[1]])


def catalog():
    out = {"categories": CATEGORIES, "emoji": [], "stickers": []}
    for kind, eid, name, label, desc, tags, cat, fname in EXPRESSIONS:
        if name in expr["removed"]: continue
        with open(os.path.join(HERE, "expressions", fname), "rb") as f:
            data = f.read()
        side = struct.unpack(">I", data[16:20])[0] if data.startswith(b"\x89PNG") else 320
        item = {"id": eid, "name": name, "description": desc, "tags": tags, "category": cat,
                "animated": fname.endswith((".webp", ".gif")),
                "file": {"id": "x_" + hashlib.sha256(data).hexdigest()[:24], "mime": MIMES[os.path.splitext(fname)[1]],
                         "width": side, "height": side, "size": len(data)}}
        if kind == "emoji": item["label"] = label
        out["emoji" if kind == "emoji" else "stickers"].append(item)
    out["version"] = hashlib.sha256(json.dumps(out, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:12]
    return out


def sticker_by(key, value):
    return next((s for s in catalog()["stickers"] if s[key] == value), None)


def sticker_line(s):
    return f"[스티커:{s['name']} — {s['description']}]" if s["description"] else f"[스티커:{s['name']}]"


def reset(empty=False):
    state.clear()
    state.update(seq=0, acked=0, box=[], files={}, history=[], hid=1200, push=None, ws=None, presence="foreground",
                 mode="기본", model="default", cards={})
    now = time.time()
    seed = [
        ("user", CONV, "내일 일정 알려 줘", -3600 * 5),
        ("lucia", CONV, "내일은 오후 2시에 치과 예약만 있어요. 그 전에 서류만 끝내 두면 돼요.", -3600 * 5 + 9),
        ("daemon", None, "좋은 아침이에요, 주인님. 오늘은 **회의가 두 개** 있어요.\n\n- 10:00 주간 회의\n- 15:00 디자인 리뷰", -3600 * 2),
        ("user", "discord:dm:1", "프린터 소리 너무 커", -1800),
        ("lucia", "discord:dm:1", "어라, 그건 시아도 신경 쓰였어요. 팬 청소부터 해 볼까요?", -1790),
    ]
    if EXPRESSIONS_ON:
        # 기록에서는 스티커가 글 속의 한 줄이다(app-expressions.md 6장). 목록에 없는 이름은 앱이 대체 표시로 그린다.
        seed += [
            ("user", CONV, "나 합격했어 :lucia_smile:\n[스티커:축하 — 별이 도는 축하]", -1500),
            ("lucia", CONV, "정말 축하해요! 그동안 고생 많았어요 :spark:\n[스티커:꾸벅]", -1490),
            ("lucia", CONV, "이건 예전에 쓰던 거예요.\n[스티커:사라진 스티커]", -1480),
        ]
    if empty:
        seed = []  # 처음 쓰는 것처럼 기록이 없다(/_dev/reset?empty=1): 앱의 빈 화면을 볼 때 쓴다.
    for frm, conv, body, dt in seed:
        add_history(frm, conv, body, now + dt)


def iso(t):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(t)) + ".000000+00:00"


def add_history(frm, conv, body, t=None):
    state["hid"] += 1
    state["history"].append({"id": state["hid"], "at": iso(t or time.time()), "from": frm, "conversation": conv, "body": body})


def welcome():
    w = _welcome()
    if EXPRESSIONS_ON:
        c = catalog()
        w["expressions"] = {"version": c["version"], "emoji": len(c["emoji"]), "stickers": len(c["stickers"])}
    if PROFILE_ON:
        w["profile"] = {"avatar": PROFILE["avatar"]}
    return w


def _welcome():
    return {"type": "welcome", "v": 2, "client": CLIENT, "name": "휴대폰 앱", "role": "app", "conversation": CONV,
            "seq": state["seq"], "scopes": list(SCOPES), "methods": sorted(["commands", "command", "history", "push.register", "push.unregister"] + ([] if LEGACY_COMMANDS else ["session.compact"])
                              + (HAND_METHODS if "browser_handover" in SCOPES else [])),
            "features": TOOLS["features"], "tools": [t["name"] for t in TOOLS["list"]], "notify_target": True, "push": {"available": True, "registered": state["push"] is not None},
            "capabilities": {"max_message_chars": 8000, "buttons": True, "edit": True, "prompts": True, "attachments": True,
                             "max_upload_bytes": 31457280, "max_message_files": 10, **({"forms": True} if FORMS_ON else {})}}


def err(status, code, message):
    return web.json_response({"error": {"code": code, "message": message}}, status=status)


@web.middleware
async def guard(request, handler):
    if "X-Forwarded-For" in request.headers or "Forwarded" in request.headers:
        return err(400, "forbidden", "출처를 알 수 없다")
    if request.path in ("/v1/health", "/v1/ws") or request.path.startswith("/_dev/"):
        return await handler(request)
    if request.headers.get("Authorization") != f"Bearer {TOKEN}":
        return err(401, "unauthorized", "토큰이 틀렸거나 취소됐다")
    return await handler(request)


# ---------------------------------------------------------------- 프레임 보내기

def push_frame(frame, numbered=True):
    if numbered:
        state["seq"] += 1
        frame = {**frame, "seq": state["seq"]}
        state["box"].append(frame)
    ws = state["ws"]
    if ws is not None and not ws.closed:
        asyncio.ensure_future(ws.send_str(json.dumps(frame, ensure_ascii=False)))
    return frame


def card(headline, title, tone="pending", fields=(), body="", footer=None, buttons=()):
    return {"headline": headline, "title": title, "fields": list(fields), "body": body, "footer": footer, "tone": tone, "buttons": list(buttons)}


def new_card_frame(kind, prior=None):
    ref = f"{CONV}:{uuid.uuid4().hex[:12]}"
    if kind == "login":
        # 로그인 입력 카드(9.1, 결정 0063). 첫 버튼에 여러 칸을 받는 form이 있다. 본문의 링크는 같은 일을 하는 다른 길이다.
        fields = [{"name": "사이트", "value": "https://example.com", "inline": True},
                  {"name": "까닭", "value": "게시판을 읽으려면 로그인이 필요합니다.", "inline": False}]
        if prior: fields.append({"name": "앞선 시도", "value": prior, "inline": False})
        c = card("🔐 **로그인이 필요합니다** 예시 사이트", "로그인 정보 입력", fields=fields,
                 body="사이트 주소가 맞는지 확인하고 [로그인 정보 입력]을 눌러 아이디와 비밀번호를 넣어 주세요(실패하면 3번까지). "
                      "넣은 값은 Lucia(모델)에게 가지 않고 데몬이 브라우저에 바로 넣습니다.\n버튼이 없거나 안 되면: https://admin.example/site-login/sample",
                 footer="요청 3f2a9c0e · 10분 안에 답이 없으면 로그인하지 않습니다",
                 buttons=[{"action": "approval:3f2a9c0e:approve", "label": "로그인 정보 입력", "primary": True, "prompt": None,
                           "form": {"title": "로그인 정보 입력", "fields": [
                               {"key": "username", "label": "아이디", "kind": "text", "min_len": 1, "max_len": 100},
                               {"key": "password", "label": "비밀번호", "kind": "secret", "min_len": 1, "max_len": 200},
                               {"key": "save", "label": "이 로그인 저장(다음부터는 승인만)", "kind": "check", "min_len": 0, "max_len": 0}]}},
                          {"action": "approval:3f2a9c0e:deny", "label": "취소", "primary": False, "prompt": None, "form": None}])
    elif kind == "code":
        c = card("인증 코드가 필요해요", "은행 로그인", buttons=[
            {"action": "code:1", "label": "코드 입력", "primary": True,
             "prompt": {"title": "인증 코드", "label": "6자리 코드", "placeholder": "123456", "min_len": 6, "max_len": 6}},
            {"action": "reject:1", "label": "취소", "primary": False, "prompt": None}])
    else:
        c = card("확인해 주세요", "메일 보내기",
                 fields=[{"name": "받는 사람", "value": "a@example.com", "inline": True}, {"name": "제목", "value": "회의록 공유", "inline": True},
                         {"name": "첨부", "value": "회의록.pdf", "inline": False}],
                 body="안녕하세요, 어제 회의록을 공유드려요.\n확인 부탁드립니다.", footer="만료 11-19 15:00 · apr-1a2b",
                 buttons=[{"action": "approve:apr-1a2b", "label": "보내기", "primary": True, "prompt": None},
                          {"action": "reject:apr-1a2b", "label": "거절", "primary": False, "prompt": None}])
    state["cards"][ref] = c
    return ref, c


def png(w=320, h=200):
    rows = b"".join(b"\x00" + bytes(v for x in range(w) for v in (140 + x * 60 // w, 200 - y * 40 // h, 245)) for y in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


DIGITS = ["111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
          "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"]
_capture = {}


def capture_png(w=1080, h=12000):
    """잔글씨가 든 긴 화면 갈무리. 40화소마다 줄 번호가 작은 숫자(6×10화소)로 적혀 있어, 키워 보았을 때 또렷한지 알 수 있다."""
    if (w, h) in _capture: return _capture[(w, h)]
    paper, ink, rule = bytes((250, 251, 253)), bytes((26, 34, 71)), bytes((221, 226, 238))
    blank = b"\x00" + paper * w
    ruled = b"\x00" + rule * w
    out = []
    for y in range(h):
        line, inside = divmod(y, 40)
        if inside == 39: out.append(ruled); continue
        if not 14 <= inside < 24: out.append(blank); continue
        dot_row = (inside - 14) // 2
        row = bytearray(blank)
        text = f"{line + 1:04d}"
        for start in range(24, w - 80, 180):
            for i, ch in enumerate(text):
                bits = DIGITS[int(ch)][dot_row * 3:dot_row * 3 + 3]
                for j, bit in enumerate(bits):
                    if bit == "1":
                        x = start + i * 8 + j * 2
                        row[1 + x * 3:1 + (x + 2) * 3] = ink * 2
        out.append(bytes(row))
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(out), 6)) + chunk(b"IEND", b"")
    _capture[(w, h)] = data
    return data


def pdf(pages=3):
    """쪽이 여럿인 작은 PDF(A4, 기본 글꼴). 쪽마다 제목 한 줄, 잔글씨 여러 줄, 네모 하나."""
    objs = [None, None, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    kids = []
    for n in range(1, pages + 1):
        lines = "".join(f"BT /F1 9 Tf 72 {700 - i * 14} Td (Line {i + 1:02d} of page {n}: the quick brown fox jumps over the lazy dog.) Tj ET\n" for i in range(36))
        stream = (f"BT /F1 26 Tf 72 760 Td (Lucent preview sample - page {n} of {pages}) Tj ET\n{lines}"
                  f"0.56 0.78 0.96 rg 72 120 451 40 re f\n").encode()
        objs.append(b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream")
        content = len(objs)
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {content} 0 R >>".encode())
        kids.append(len(objs))
    objs[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objs[1] = f"<< /Type /Pages /Kids [{' '.join(f'{k} 0 R' for k in kids)}] /Count {pages} >>".encode()
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n".encode() + b"0000000000 65535 f \n" + b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def zipped(names):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name in names: z.writestr(name, os.urandom(40000))
    return buf.getvalue()


TEXT_MIMES = {"md": "text/markdown", "csv": "text/csv", "json": "application/json", "html": "text/html", "htm": "text/html",
              "svg": "image/svg+xml", "xml": "application/xml", "ics": "text/calendar"}


def detect(name, data):
    """종류와 MIME을 파일 앞부분의 서명으로 정한다(데몬의 `files.rs` `detect`와 같은 순서)."""
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if data.startswith(b"\x89PNG\r\n\x1a\n"): return "image", "image/png"
    if data.startswith(b"\xff\xd8\xff"): return "image", "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")): return "image", "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP": return "image", "image/webp"
    if data.startswith(b"%PDF-"): return "pdf", "application/pdf"
    if data.startswith(b"PK\x03\x04"): return "other", "application/zip"
    head = data[:64 * 1024]
    if data and b"\x00" not in head:
        try:
            head.decode("utf-8")
        except UnicodeDecodeError as e:
            if not (len(head) == 64 * 1024 and e.start + 4 >= len(head)): return "other", "application/octet-stream"
        return "text", TEXT_MIMES.get(ext, "text/plain")
    return "other", "application/octet-stream"


def store_file(name, data, gone=False):
    """[gone]이면 목록에는 있지만 받으려 하면 410이다(보관 기간이 지난 파일)."""
    fid = "f_" + uuid.uuid4().hex[:12]
    kind, mime = detect(name, data)
    state["files"][fid] = (name, data, mime, gone)
    return {"id": fid, "name": name, "size": len(data), "mime": mime, "kind": kind}


PREVIEW_MD = """# 11월 지출 정리

이번 달은 **식비**가 가장 컸어요. 지난달보다 *8%* 늘었습니다.

## 항목

- 식비 320,000원
- 교통 45,000원
- 구독 3건
  - 음악 10,900원
  - 영상 17,000원

> 다음 달부터는 주간 한도를 두는 걸 권해요.

1. 주간 한도 정하기
2. 일요일마다 돌아보기

`합계 = 식비 + 교통 + 구독`

## 주별

| 주 | 식비 | 교통 | 메모 |
|:--|--:|--:|:--|
| 첫째 주 | 72,000 | 11,000 | 회식 한 번 |
| 둘째 주 | 81,500 | 9,800 | **가장 큼** |
| 셋째 주 | 79,000 | 12,400 | `배달` 세 번 |
| 넷째 주 | 87,500 | 11,800 | |

## 구독 살펴보기

| 서비스 | 요금 | 결제일 | 지난달 쓴 시간 | 나눠 쓰는 사람 | 다음 달에 어떻게 할지 |
|---|--:|:-:|--:|---|---|
| 음악 | 10,900 | 3일 | 41시간 | 혼자 | 그대로 둔다 |
| 영상 | 17,000 | 12일 | 6시간 | 가족 넷 | 한 달 쉬어 보고 다시 정한다 |

```python
total = sum(item.amount for item in items)
print(f"{total:,}원")
```

자세한 방법은 [가계부 쓰는 법](https://example.com/guide)을 봐 주세요. 글 속의 :lucia_smile: 은 파일에서는 그리지 않아요.
"""

PREVIEW_KT = """package dev.mooner.sample

/** 한 달 지출을 항목별로 더한다. */
data class Expense(val name: String, val amount: Int)

fun total(expenses: List<Expense>): Int = expenses.sumOf { it.amount }

fun main() {
    val november = listOf(Expense("식비", 320_000), Expense("교통", 45_000), Expense("구독", 27_900))
    println("합계: ${total(november)}원 — 항목 ${november.size}개, 가장 큰 것은 ${november.maxBy { it.amount }.name}")
}
"""


def preview_files():
    """앱의 파일 미리보기를 시험하는 견본 세 묶음: 글로 보이는 것, 그림·PDF로 보이는 것, 보일 수 없는 것."""
    csv = "날짜,내용,금액\r\n" + "".join(f"11-{d:02d},점심,{8000 + d * 100}\r\n" for d in range(1, 21))
    log = "".join(f"2026-11-19 09:{i // 60 % 60:02d}:{i % 60:02d} INFO 일정 동기화 {i}번째 묶음을 마쳤습니다\n" for i in range(6000))
    prose = ("오늘 회의에서는 다음 분기의 일정과 맡을 사람을 정했어요. 긴 문단은 화면 폭에 맞춰 줄이 꺾여야 읽기 편합니다. " * 3 + "\n\n") * 4
    one_line = json.dumps({f"key{i}": i for i in range(120)}, separators=(",", ":"))
    texts = [
        store_file("11월 지출 정리.md", PREVIEW_MD.encode()),
        store_file("Expense.kt", PREVIEW_KT.encode()),
        store_file("2026년 11월 셋째 주 회의록 - 일정 조율과 다음 분기 계획 초안 (검토용, 세 번째 고침).txt", "이름이 긴 파일이에요.\n".encode()),
        store_file("회의록.txt", prose.encode()),
        store_file("거래내역.csv", csv.encode("cp949")),
        store_file("동기화.log", log.encode()),
        store_file("한 줄.json", one_line.encode()),
    ]
    pictures = [
        store_file("긴 화면.png", png(360, 4200)),
        store_file("긴 갈무리.png", capture_png()),
        store_file("작은 표시.png", png(48, 48)),
        store_file("움직임.gif", open(os.path.join(HERE, "expressions", "party.gif"), "rb").read()),
        store_file("보고서.pdf", pdf(3)),
    ]
    hard = [
        store_file("깨진 글.txt", bytes(range(1, 256)) * 8),
        store_file("깨진 그림.heic", b"not really an image" * 40 + b"\xff\xfe"),
        store_file("깨진 보고서.pdf", b"%PDF-1.4\n%%EOF\n"),
        store_file("묶음.zip", zipped(["a.bin", "b.bin", "c.bin"])),
        store_file("지워진 묶음.zip", zipped(["a.bin"]), gone=True),
        store_file("지워진 메모.md", "# 지워진 메모\n".encode(), gone=True),
    ]
    return texts, pictures, hard


def send_sticker(name):
    """번호 붙는 `sticker` 프레임(5.1). 목록에 없으면 보내지 않는다."""
    s = sticker_by("name", name)
    if s is None: return None
    return push_frame({"type": "sticker", "sticker": {"id": s["id"], "name": s["name"]}})


async def reply_to(text, sticker=None):
    ws = state["ws"]
    push_frame({"type": "typing"}, numbered=False)
    await asyncio.sleep(1.2)
    if sticker is not None and not text:
        body = f"{sticker['name']} 스티커네요, 고마워요 :lucia_smile:"
        push_frame({"type": "text", "body": body})
        add_history("lucia", CONV, body)
        return
    if any(w in text for w in ("프로필 없애", "noavatar")):
        set_avatar("none")
        body = "프로필 그림을 없앴어요. 원래 모습으로 돌아갈게요."
        push_frame({"type": "text", "body": body}); add_history("lucia", CONV, body)
        return
    if any(w in text for w in ("프로필", "avatar")):
        # 견본 둘을 번갈아 쓴다. 지금 것이 첫 견본이면 둘째로.
        first = "a_" + hashlib.sha256(avatar_bytes("1")[0]).hexdigest()[:24]
        set_avatar("2" if PROFILE["avatar"] and PROFILE["avatar"]["id"] == first else "1")
        body = "프로필 그림을 바꿨어요. 어때요?"
        push_frame({"type": "text", "body": body}); add_history("lucia", CONV, body)
        return
    if any(w in text for w in ("위치", "where")):
        await tool_reply("location.get", {"precise": True},
                         lambda d: f"지금 위도 {d.get('lat')}, 경도 {d.get('lng')} 근처예요(오차 {d.get('accuracy_m')} m).")
        return
    if any(w in text for w in ("받은 알림", "notices")):
        await tool_reply("notifications.recent", {"limit": 10},
                         lambda d: f"최근 알림 {len(d.get('items') or [])}개를 봤어요" + (" (더 있어요)" if d.get("more") else "") + ".")
        return
    if any(w in text for w in ("취소", "revoke")):
        await ws.send_str(json.dumps({"type": "error", "code": "unauthorized", "message": "토큰이 취소됐다"}))
        await ws.close()
        return
    if any(w in text for w in ("끊어", "dropme")):
        await ws.close()
        return
    if any(w in text for w in ("알림", "note")):
        push_frame({"type": "note", "body": "메일함을 확인하고 있어요"})
        await asyncio.sleep(1.0)
    if any(w in text for w in ("로그인", "login")):
        # 로그인 입력 카드. 넣는 아이디가 결과를 정한다: ok(성공), already(이미 로그인), code(인증 코드 카드),
        # captcha(사람 확인 안내), 그 밖은 실패해 새 카드가 온다(3번까지).
        state["login_tries"] = 0
        ref, c = new_card_frame("login")
        push_frame({"type": "card", "ref": ref, "card": c})
        return
    if any(w in text for w in ("카드", "코드", "card", "code")):
        ref, c = new_card_frame("code" if any(w in text for w in ("코드", "code")) else "mail")
        push_frame({"type": "text", "body": "보내기 전에 확인받을게요."})
        await asyncio.sleep(0.5)
        push_frame({"type": "card", "ref": ref, "card": c})
        return
    if any(w in text for w in ("미리보기", "preview")):
        texts, pictures, hard = preview_files()
        push_frame({"type": "files", "body": "글로 된 것들이에요.", "files": texts})
        await asyncio.sleep(0.4)
        push_frame({"type": "files", "body": "그림과 PDF예요.", "files": pictures})
        await asyncio.sleep(0.4)
        push_frame({"type": "files", "body": "이건 앱이 보이기 어려운 것들이에요.", "files": hard})
        return
    if any(w in text for w in ("이미지", "image")):
        f = store_file("노을.png", png())
        push_frame({"type": "files", "body": "이런 색 어때요?", "files": [f]})
        return
    if any(w in text for w in ("파일", "file")):
        f1 = store_file("11월 지출 정리.md", "# 11월 지출\n\n- 식비 320,000원\n- 교통 45,000원\n".encode())
        push_frame({"type": "files", "body": "정리했어요. 한번 봐 주세요.", "files": [f1]})
        return
    if any(w in text for w in ("스티커", "sticker")):
        # 글은 `text`로, 스티커는 바로 뒤의 `sticker` 프레임으로 간다. 기록에는 마지막 줄 `[스티커:이름]`으로 남는다.
        body = "정말 축하해요! 그동안 고생 많았어요 :spark:"
        push_frame({"type": "text", "body": body})
        await asyncio.sleep(0.6)
        sent = send_sticker("축하")
        add_history("lucia", CONV, body + ("\n[스티커:축하]" if sent else ""))
        return
    if any(w in text for w in ("이모지", "emoji")):
        body = ("다 됐어요 :ok_check: 오늘도 수고했어요 :lucia_smile:\n\n"
                "목록에 없는 건 글 그대로예요: :not_ours: 10:30:00 std::fmt::Display\n"
                "막은 것: \\:lucia_smile: · 코드 안: `:lucia_smile:` · 붙여 쓴 것: :spark::moon_night:")
        push_frame({"type": "text", "body": body})
        add_history("lucia", CONV, body)
        await asyncio.sleep(0.8)
        push_frame({"type": "text", "body": ":lucia_wow: :spark:"})
        add_history("lucia", CONV, ":lucia_wow: :spark:")
        return
    if any(w in text for w in ("긴글", "long")):
        body = "오, 긴 이야기네요. 천천히 정리해 볼게요.\n\n**할 일**\n1. 서류 제출\n2. 치과 예약 확인\n3. 프린터 팬 청소\n\n> 급한 건 서류예요.\n\n자세한 건 [여기](https://example.com/docs)를 봐 주세요. 코드는 `luciad check`로 확인해요.\n\n```\ncargo run -p luciad -- check\n```"
        push_frame({"type": "text", "body": body})
        add_history("lucia", CONV, body)
        return
    answer = f"네, 들었어요. \"{text[:40]}\"요? 그건 시아가 맡을게요."
    push_frame({"type": "text", "body": answer})
    await asyncio.sleep(1.0)
    push_frame({"type": "text", "body": "끝나면 바로 알려 드릴게요."})
    add_history("lucia", CONV, answer)


# ---------------------------------------------------------------- WebSocket

async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    try:
        msg = await asyncio.wait_for(ws.receive(), 10)
    except asyncio.TimeoutError:
        await ws.close(); return ws
    if msg.type != WSMsgType.TEXT:
        return ws
    hello = json.loads(msg.data)
    if hello.get("type") != "hello":
        await ws.send_str(json.dumps({"type": "error", "code": "hello_required", "message": "hello가 먼저"})); await ws.close(); return ws
    if hello.get("token") != TOKEN:
        await ws.send_str(json.dumps({"type": "error", "code": "unauthorized", "message": "토큰이 틀렸다"})); await ws.close(); return ws
    old = state["ws"]
    if old is not None and not old.closed:
        await old.close()
    state["ws"] = ws
    state["presence"] = "foreground"
    TOOLS["list"] = clean_tools(hello.get("tools") or [])
    TOOLS["features"] = clean_features(list(hello.get("features") or []) + [t["name"].split(".")[0] for t in TOOLS["list"]])
    print(f"[ws] hello resume={hello.get('resume')} features={hello.get('features')} tools={[t['name'] + ('(write)' if t['write'] else '') for t in TOOLS['list']]}")
    await ws.send_str(json.dumps(welcome(), ensure_ascii=False))
    resume = hello.get("resume")
    if resume is not None:
        replay = [f for f in state["box"] if f["seq"] > resume][:500]
        for f in replay:
            await ws.send_str(json.dumps({**f, "replay": True}, ensure_ascii=False))
        to = replay[-1]["seq"] if replay else resume
        await ws.send_str(json.dumps({"type": "resumed", "from": resume, "to": to, "last": state["seq"], "truncated": False}))
    async for m in ws:
        if m.type != WSMsgType.TEXT:
            if m.type == WSMsgType.BINARY:
                await ws.send_str(json.dumps({"type": "error", "code": "bad_frame", "message": "글 프레임만"}))
            continue
        try:
            f = json.loads(m.data)
        except ValueError:
            await ws.send_str(json.dumps({"type": "error", "code": "bad_frame", "message": "깨진 JSON"})); continue
        t = f.get("type")
        if t == "ack":
            state["acked"] = max(state["acked"], f.get("seq", 0))
        elif t == "ping":
            await ws.send_str('{"type":"pong"}')
        elif t == "presence":
            state["presence"] = f.get("state"); print("[ws] presence", f.get("state"))
        elif t == "message":
            text = (f.get("text") or "").strip(); fids = f.get("files") or []
            sid = (f.get("sticker") or "").strip() or None
            if not text and not fids and not sid:
                await ws.send_str(json.dumps({"type": "error", "code": "empty", "message": "빈 말"})); continue
            sticker = None
            if sid:
                sticker = sticker_by("id", sid) if EXPRESSIONS_ON else None
                if sticker is None:
                    await ws.send_str(json.dumps({"type": "error", "code": "bad_sticker", "message": "목록에 없는 스티커"})); continue
            bad = [x for x in fids if x not in state["files"]]
            if bad:
                await ws.send_str(json.dumps({"type": "error", "code": "bad_file", "message": bad[0]})); continue
            await ws.send_str(json.dumps({"type": "accepted", "id": f.get("id")}))
            # 스티커는 `<글>\n[스티커:이름 — 설명]`으로 루시아에게 가고 기록에도 그렇게 남는다(5.2).
            add_history("user", CONV, "\n".join(x for x in (text, sticker_line(sticker) if sticker else "") if x))
            print("[ws] message", text, fids, "sticker=" + str(sid))
            asyncio.ensure_future(reply_to(text, sticker))
        elif t == "press":
            ref = f.get("ref"); c = state["cards"].get(ref)
            inputs = f.get("inputs")
            # 값은 찍지 않는다. 칸 이름과 길이만 남긴다(불은 그대로).
            shape = None if not isinstance(inputs, dict) else {k: (v if isinstance(v, bool) else len(v) if isinstance(v, str) else "?") for k, v in inputs.items()}
            print("[ws] press", ref, f.get("action"), "input=" + ("<있음>" if f.get("input") else "없음"), "inputs=" + (json.dumps(shape, ensure_ascii=False) if shape is not None else "없음"))
            if "inputs" in f and (not isinstance(inputs, dict) or len(inputs) > 8 or any(
                    not k or len(k) > 40 or not isinstance(v, (str, bool)) or (isinstance(v, str) and len(v) > 1000) for k, v in inputs.items())):
                await ws.send_str(json.dumps({"type": "error", "code": "bad_frame", "message": "입력 칸의 모양이 틀렸다"})); continue
            if c is None:
                await ws.send_str(json.dumps({"type": "error", "code": "bad_ref", "message": "없는 카드"})); continue
            if any(b.get("form") for b in c["buttons"]):
                await press_login(ref, c, f.get("action", ""), inputs, shape)
                continue
            await asyncio.sleep(0.8)
            ok = f.get("action", "").startswith(("approve", "code"))
            closed = {**c, "tone": "success" if ok else "danger", "headline": "✅ 보냈습니다" if ok else "❌ 거절했어요", "buttons": []}
            state["cards"].pop(ref, None)
            push_frame({"type": "card_closed", "ref": ref, "card": closed})
        elif t == "call":
            await handle_call(ws, f)
        elif t == "result":
            waiting = TOOLS["pending"].pop(f.get("id"), None)
            if waiting is None or waiting.done():
                await ws.send_str(json.dumps({"type": "error", "code": "bad_ref", "message": "기다리는 부탁이 아니다"}))
                continue
            waiting.set_result(f)
        else:
            await ws.send_str(json.dumps({"type": "error", "code": "bad_frame", "message": "모르는 type"}))
    if state["ws"] is ws:
        state["ws"] = None
        # 연결이 끊기면 기다리던 부탁은 실패로 끝난다(7.1).
        for rid, fut in list(TOOLS["pending"].items()):
            TOOLS["pending"].pop(rid, None)
            if not fut.done():
                fut.set_result({"ok": False, "error": {"code": "offline", "message": "앱 연결이 끊겼다"}})
    return ws


async def call_app(method, params):
    """루시아의 app_call처럼 앱에 부탁하고 답을 기다린다(사양 7장). 돌려주는 것은 앱의 result 프레임(또는 같은 모양의 실패)."""
    ws = state["ws"]
    tool = next((t for t in TOOLS["list"] if t["name"] == method), None)
    if "app_tools" not in SCOPES:
        return {"ok": False, "error": {"code": "forbidden", "message": "이 토큰에는 app_tools 범위가 없다"}}
    if ws is None or ws.closed:
        return {"ok": False, "error": {"code": "offline", "message": "앱이 연결되어 있지 않다"}}
    if tool is None:
        return {"ok": False, "error": {"code": "unsupported", "message": "그 기능을 내주는 앱이 지금 연결되어 있지 않다"}}
    rid = "d-" + uuid.uuid4().hex[:12]
    fut = asyncio.get_event_loop().create_future()
    TOOLS["pending"][rid] = fut
    await ws.send_str(json.dumps({"type": "invoke", "id": rid, "method": method, "params": params}, ensure_ascii=False))
    started = time.time()
    try:
        res = await asyncio.wait_for(fut, APP_CALL_TIMEOUT)
    except asyncio.TimeoutError:
        TOOLS["pending"].pop(rid, None)
        res = {"ok": False, "error": {"code": "timeout", "message": "앱이 40초 안에 답하지 않았다"}}
    # 결과의 글은 남기지 않는다(위치, 알림은 사용자의 비공개 자료다).
    took = time.time() - started
    if res.get("ok"):
        print(f"[tool] {method} 성공 {took:.1f}초, 결과 {len(json.dumps(res.get('data'), ensure_ascii=False).encode())}바이트")
    else:
        print(f"[tool] {method} 실패({(res.get('error') or {}).get('code')}) {took:.1f}초")
    return res


async def tool_reply(method, params, done):
    """말로 부른 앱 기능의 결과를 루시아의 말처럼 답한다."""
    res = await call_app(method, params)
    if res.get("ok"):
        body = done(res.get("data") or {})
    else:
        err = res.get("error") or {}
        body = f"휴대폰에 물어보지 못했어요({err.get('code')}): {err.get('message', '')}"
    push_frame({"type": "text", "body": body})
    add_history("lucia", CONV, body)


def model_label():
    key = state["model"]
    return "기본(sonnet)" if key == "default" else next(label for k, label, _ in MODELS if k == key)


async def press_login(ref, c, action, inputs, shape):
    """로그인 입력 카드를 눌렀다(9.1). 카드는 바로 닫히고("받았다"), 결과는 뒤에 글이나 새 카드로 온다."""
    if action.endswith(":deny"):
        state["cards"].pop(ref, None)
        push_frame({"type": "card_closed", "ref": ref, "card": {**c, "tone": "danger", "headline": "🚫 거절했습니다. 로그인하지 않았습니다.", "buttons": []}})
        return
    if inputs is None:
        return  # form이 있는 버튼을 inputs 없이 누르면 아무 일도 없다(카드는 열린 채 남는다).
    LAST_INPUTS.clear(); LAST_INPUTS.update(shape)
    user = inputs.get("username") if isinstance(inputs.get("username"), str) else ""
    state["cards"].pop(ref, None)
    push_frame({"type": "card_closed", "ref": ref, "card": {**c, "tone": "neutral", "headline": "⏳ 로그인하고 있습니다. 결과는 곧 알려 드립니다.", "buttons": []}})
    await asyncio.sleep(1.5)
    if user == "ok":
        push_frame({"type": "text", "body": "✅ 예시 사이트에 로그인했습니다. 다음부터는 저장된 로그인을 승인만 하면 됩니다. 하던 일을 다시 말씀해 주세요."})
    elif user == "already":
        push_frame({"type": "text", "body": "✅ 예시 사이트: 이미 로그인돼 있었습니다. 넣은 비밀번호는 확인하지 못해 저장하지 않았습니다."})
    elif user == "code":
        r, card2 = new_card_frame("code")
        push_frame({"type": "card", "ref": r, "card": card2})
    elif user == "captcha":
        push_frame({"type": "text", "body": "🧩 예시 사이트이(가) 사람 확인(캡차)을 띄워 아직 로그인되지 않았습니다. 10분 안에 앱의 브라우저 화면 넘겨받기"
                                            "(또는 관리 페이지 \"브라우저 넘겨받기\")에서 그 화면을 직접 풀어 주세요: https://admin.example/browser/handover"})
    else:
        state["login_tries"] = state.get("login_tries", 0) + 1
        if state["login_tries"] >= 3:
            push_frame({"type": "text", "body": "⚠️ 예시 사이트 이 링크로 3번 시도해 닫았습니다. 다시 하려면 Lucia에게 요청해 주세요."})
        else:
            why = "⚠️ 사이트가 아이디나 비밀번호를 받지 않았습니다."
            push_frame({"type": "text", "body": "⚠️ 예시 사이트 로그인하지 못했습니다: 사이트가 받지 않았습니다. 새로 올린 카드에서 다시 넣을 수 있습니다."})
            r, card2 = new_card_frame("login", prior=why)
            push_frame({"type": "card", "ref": r, "card": card2})


def hand_state():
    if HAND["active"] and time.time() >= HAND["until"]:
        HAND["active"] = False  # 최대 시간이 지나면 데몬이 끝낸다.
    tabs = []
    if HAND["active"]:
        tabs = [{"id": "TAB1", "url": "https://example.com/login", "title": "로그인 · 예시 사이트"}]
        if HAND["popup"]: tabs.append({"id": "TAB2", "url": "https://example.com/verify", "title": "본인 확인"})
    return {"active": HAND["active"], "left_seconds": int(HAND["until"] - time.time()) if HAND["active"] else None,
            "max_seconds": HAND_MAX, "tabs": tabs}


async def hand_call(method, params, ok, bad):
    """`browser.handover.*`(6.7). 넣은 글과 누른 자리는 시험이 볼 수 있게 모양만 남긴다(글은 길이)."""
    if method == "browser.handover.state":
        return await ok(hand_state())
    if method == "browser.handover.start":
        if not hand_state()["active"]:
            HAND.update(active=True, until=time.time() + HAND_MAX, checked=False)
        HAND["starts"] += 1
        print("[handover] start")
        return await ok(hand_state())
    if method == "browser.handover.end":
        HAND["active"] = False; HAND["ends"] += 1
        print("[handover] end")
        return await ok({"active": False, "warning": None})
    tab, kind = params.get("tab"), params.get("kind")
    def whole(v, low=None): return isinstance(v, int) and not isinstance(v, bool) and (low is None or v >= low)
    if not isinstance(tab, str) or not tab or kind not in ("click", "text", "key", "scroll"):
        return await bad("bad_params", "tab이나 kind가 틀렸다")
    if kind == "click" and not (whole(params.get("x"), 0) and whole(params.get("y"), 0)): return await bad("bad_params", "좌표가 틀렸다")
    if kind == "text" and not (isinstance(params.get("text"), str) and 1 <= len(params["text"]) <= 2000): return await bad("bad_params", "글이 틀렸다")
    if kind == "key" and not isinstance(params.get("key"), str): return await bad("bad_params", "키가 없다")
    if kind == "scroll" and not whole(params.get("dy")): return await bad("bad_params", "dy가 틀렸다")
    if not hand_state()["active"]:
        return await bad("not_active", "넘겨받은 상태가 아니다")
    if tab not in ("TAB1", "TAB2"): return await bad("failed", "없는 탭")
    if kind == "click":
        x, y = params["x"], params["y"]
        HAND["clicks"].append([x, y]); del HAND["clicks"][:-20]
        l, t0, r, b = HAND_BOX
        if l <= x < r and t0 <= y < b: HAND["checked"] = True
        print("[handover] click", x, y)
    elif kind == "text":
        HAND["text_lens"].append(len(params["text"])); del HAND["text_lens"][:-20]
        print("[handover] text", len(params["text"]), "자")
    elif kind == "key":
        if params["key"] not in ("enter", "tab", "backspace", "escape", "up", "down"): return await bad("failed", "모르는 키")
        HAND["keys"].append(params["key"]); del HAND["keys"][:-20]
        print("[handover] key", params["key"])
    else:
        HAND["scroll"] += max(-3000, min(3000, params["dy"]))
        print("[handover] scroll", params["dy"])
    await ok({"sent": True})


def hand_frame_bytes():
    """넘겨받은 브라우저의 화면 한 장. Pillow가 있으면 JPEG로 그리고(사람 확인 칸, 마지막으로 누른 자리, 넣은 글의 길이),
    없으면 같은 크기의 PNG를 보낸다(앱은 내용으로 가려 읽는다)."""
    w, h = HAND_SIZE
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return png(w, h)
    im = Image.new("RGB", (w, h), (246, 248, 252)); d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w, 56), fill=(36, 103, 166))
    for x in range(0, w, 100):  # 좌표를 눈으로 읽을 수 있게 100화소마다 눈금을 긋는다.
        d.line((x, 56, x, h), fill=(228, 233, 242)); d.text((x + 4, 60), str(x), fill=(120, 130, 150))
    for y in range(100, h, 100):
        d.line((0, y, w, y), fill=(228, 233, 242)); d.text((4, y + 4), str(y), fill=(120, 130, 150))
    off = HAND["scroll"]
    l, t0, r, b = HAND_BOX
    d.rectangle((420, 300 - off, 945, 480 - off), outline=(120, 130, 150), width=3, fill=(255, 255, 255))
    d.rectangle((l, t0 - off, r, b - off), outline=(36, 103, 166), width=4, fill=(92, 184, 122) if HAND["checked"] else (255, 255, 255))
    d.rectangle((620, 375 - off, 900, 405 - off), fill=(52, 66, 107))
    d.rectangle((420, 540 - off, 945, 600 - off), outline=(120, 130, 150), width=3, fill=(255, 255, 255))
    for i in range(min(sum(HAND["text_lens"]), 30)): d.ellipse((440 + i * 16, 562 - off, 450 + i * 16, 572 - off), fill=(26, 34, 71))
    if HAND["clicks"]:
        x, y = HAND["clicks"][-1]; d.ellipse((x - 12, y - 12, x + 12, y + 12), outline=(214, 69, 96), width=4)
    out = io.BytesIO(); im.save(out, "JPEG", quality=70)
    return out.getvalue()


async def hand_frame(request):
    if "browser_handover" not in SCOPES: return err(403, "forbidden", "범위가 없다")
    if not hand_state()["active"]: return err(409, "not_active", "넘겨받은 상태가 아니다")
    tab = request.match_info["tab"].removesuffix(".jpg")
    if tab not in ("TAB1", "TAB2") or (tab == "TAB2" and not HAND["popup"]): return err(404, "not_found", "없는 탭")
    HAND["frames"] += 1
    if SLOW["ms"]: await asyncio.sleep(SLOW["ms"] / 1000)
    return web.Response(body=hand_frame_bytes(), content_type="image/jpeg",
                        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})


async def dev_handover(request):
    """/_dev/handover?expire=1(데몬이 시간이 다 돼 끝낸 것처럼), ?popup=1|0(새 창이 탭으로 생기거나 사라진다) : 넘겨받기를 조작하고 기록을 본다."""
    q = request.query
    if q.get("expire"): HAND["until"] = 0.0
    if "popup" in q: HAND["popup"] = q["popup"] == "1"
    hand_state()
    return web.json_response({k: v for k, v in HAND.items() if k != "until"} | {"last_inputs": dict(LAST_INPUTS)})


async def handle_call(ws, f):
    rid, method, params = f.get("id"), f.get("method"), f.get("params") or {}
    def ok(data): return ws.send_str(json.dumps({"type": "result", "id": rid, "ok": True, "data": data}, ensure_ascii=False))
    def bad(code, msg): return ws.send_str(json.dumps({"type": "result", "id": rid, "ok": False, "error": {"code": code, "message": msg}}, ensure_ascii=False))
    if isinstance(method, str) and method.startswith("browser.handover."):
        if "browser_handover" not in SCOPES: return await bad("forbidden", "범위가 없다")
        if method not in HAND_METHODS: return await bad("unknown_method", "없는 메서드")
        return await hand_call(method, params if isinstance(params, dict) else {}, ok, bad)
    can_change = "security_mode" in SCOPES
    if method == "commands":
        mode = {"name": "권한", "about": "권한 모드 보기·바꾸기", "options": list(MODES), "can_change": can_change}
        if LEGACY_COMMANDS:
            await ok({"commands": [mode]})
        else:
            mode["current"] = state["mode"]
            model = {"name": "모델", "about": "주 모델 보기·바꾸기", "options": [m[0] for m in MODELS] + ["default"],
                     "current": state["model"], "can_change": True}
            await ok({"commands": [mode, model]})
    elif method == "command" and params.get("name") == "모델" and not LEGACY_COMMANDS:
        option = params.get("option")
        if option is None:
            lines = "".join(f"\n- {label}: {about}" for _, label, about in MODELS)
            await ok({"text": f"주 모델: {model_label()}\n고를 수 있는 모델:{lines}\n- 기본: 설정 파일 값(sonnet)\n{MODEL_NOTE}"})
        else:
            key = "default" if option.strip() in ("기본", "default") else option.strip().lower()
            if key != "default" and key not in [m[0] for m in MODELS]:
                # 모르는 값은 오류가 아니라 안내 글로 온다. 바뀌지 않는다.
                await ok({"text": "모델은 fable, opus, sonnet, haiku, 기본 중 하나입니다."})
            elif key == state["model"]:
                await ok({"text": f"이미 {model_label()}입니다."})
            else:
                state["model"] = key
                print(f"[ws] model -> {key}")
                await ok({"text": f"주 모델을 {model_label()}(으)로 바꿨습니다.\n{MODEL_NOTE}"})
    elif method == "session.compact" and not LEGACY_COMMANDS:
        min_tokens = params.get("min_tokens", 0)
        print(f"[ws] session.compact min_tokens={min_tokens} {COMPACT}")
        if isinstance(min_tokens, bool) or not isinstance(min_tokens, int) or min_tokens < 0: await bad("bad_params", "min_tokens는 0 이상의 정수")
        elif COMPACT["error"]: await bad(COMPACT["error"], "정리하지 못했습니다")
        elif COMPACT["busy"] > 0:
            COMPACT["busy"] -= 1
            await bad("busy", "이미 정리하는 중입니다")
        elif COMPACT["running"]: await bad("busy", "이미 정리하는 중입니다")
        elif COMPACT["done"]:
            # 마지막 정리 뒤에 새 대화가 없다: 모델을 부르지 않고 바로 답한다.
            await ok({"outcome": "unchanged", "context_tokens": 2778, "text": "마지막 정리 뒤에 새 대화가 없어 다시 정리하지 않았습니다."})
        else:
            # 답은 정리가 끝난 뒤에 온다. 그동안 다른 프레임은 그대로 오간다. 연결이 끊겨도 정리는 계속된다.
            async def run():
                COMPACT["running"] = True
                await asyncio.sleep(COMPACT["ms"] / 1000)
                COMPACT["running"] = False
                outcome = COMPACT["outcome"]
                data = {"compacted": {"outcome": "compacted", "pre_tokens": 128662, "context_tokens": 2778, "text": "대화를 정리했습니다(128,662→2,778 토큰)."},
                        "small": {"outcome": "small", "context_tokens": 41200, "text": "대화가 작아 정리하지 않았습니다(41,200 토큰)."},
                        "none": {"outcome": "none", "context_tokens": 0, "text": "정리할 대화가 없습니다."},
                        "reset": {"outcome": "reset", "context_tokens": 0, "text": "정리하지 못해 새 세션으로 넘어갔습니다. 기억과 최근 대화로 이어 갑니다."},
                        }.get(outcome, {"outcome": outcome, "context_tokens": 1, "text": f"끝났습니다({outcome})."})
                COMPACT["done"] = outcome == "compacted"
                print(f"[ws] session.compact done {outcome}")
                try: await ok(data)
                except Exception: pass
            asyncio.create_task(run())
    elif method == "command":
        option = params.get("option")
        if params.get("name") != "권한": await bad("unknown_command", "없는 명령")
        elif option and not can_change: await bad("forbidden", "이 토큰에는 권한이 없다")
        elif option and MODE_ALIASES.get(option, option) not in MODES:
            # 모드 값이 틀리면 오류가 아니라 안내 글이 온다(6.4).
            await ok({"text": "🛡️ 쓸 수 있는 모드는 엄격, 기본, 해제예요."})
        else:
            if option: state["mode"] = MODE_ALIASES.get(option, option)
            await ok({"text": f"🛡️ 권한 모드: **{state['mode']}** — {MODES[state['mode']]}"})
    elif method == "history":
        await ok(history_page(params.get("before"), params.get("limit", 50), params.get("scope", "app")))
    else:
        await bad("unknown_method", "없는 메서드")


def history_page(before, limit, scope):
    items = [h for h in state["history"] if scope == "owner" or h["conversation"] == CONV]
    if before: items = [h for h in items if h["id"] < int(before)]
    limit = max(1, min(100, int(limit)))
    page = items[-limit:]
    return {"items": page, "more": len(items) > len(page)}


# ---------------------------------------------------------------- HTTP

async def health(_): return web.Response(text="ok")

async def me(_):
    w = welcome(); w.pop("type"); w.pop("capabilities"); w.pop("features"); w.pop("notify_target")
    w.update(acked=state["acked"], online=state["ws"] is not None and not state["ws"].closed)
    return web.json_response(w, dumps=lambda o: json.dumps(o, ensure_ascii=False))

async def expressions(request):
    if not EXPRESSIONS_ON: return err(501, "unsupported", "이 데몬은 이모지·스티커를 다루지 않는다")
    c = catalog(); etag = f'"{c["version"]}"'
    headers = {"ETag": etag, "Cache-Control": "private, no-cache"}
    asked = [t.strip().removeprefix("W/") for t in request.headers.get("If-None-Match", "").split(",")]
    print(f"[http] expressions if-none-match={request.headers.get('If-None-Match')} -> {'304' if etag in asked else '200'}")
    if etag in asked: return web.Response(status=304, headers=headers)
    return web.json_response(c, headers=headers, dumps=lambda o: json.dumps(o, ensure_ascii=False))

async def expression_file(request):
    f = expr["files"].get(request.match_info["id"]) if EXPRESSIONS_ON else None
    if f is None: return err(404, "not_found", "없는 그림")
    data, mime = f
    print(f"[http] expression file {request.match_info['id']} {len(data)}B")
    return web.Response(body=data, content_type=mime, headers={
        "ETag": f'"{request.match_info["id"]}"', "X-Content-Type-Options": "nosniff", "Cache-Control": "private, max-age=31536000, immutable"})

async def profile(_):
    if not PROFILE_ON: return err(501, "unsupported", "이 데몬은 프로필 이미지를 다루지 않는다")
    return web.json_response({"avatar": PROFILE["avatar"]}, headers={"Cache-Control": "private, no-cache"})

async def profile_avatar(request):
    if not PROFILE_ON: return err(501, "unsupported", "이 데몬은 프로필 이미지를 다루지 않는다")
    aid = request.match_info["id"]
    data = PROFILE["files"].get(aid)
    if data is None:
        print(f"[http] avatar {aid} -> 404")
        return err(404, "not_found", "없는 그림")
    if FAIL["n"] > 0:
        FAIL["n"] -= 1
        print(f"[http] avatar {aid} -> 503")
        return err(503, "unavailable", "잠시 뒤에 다시 해 주세요")
    print(f"[http] avatar {aid} {len(data)}B")
    headers = {"ETag": f'"{aid}"', "X-Content-Type-Options": "nosniff", "Cache-Control": "private, max-age=31536000, immutable"}
    mime = PROFILE["avatar"]["mime"] if PROFILE["avatar"] and PROFILE["avatar"]["id"] == aid else "image/png"
    if not SLOW["ms"]:
        return web.Response(body=data, content_type=mime, headers=headers)
    resp = web.StreamResponse(headers={**headers, "Content-Type": mime, "Content-Length": str(len(data))})
    await resp.prepare(request)
    step = max(1, len(data) // 10)
    for i in range(0, len(data), step):
        await resp.write(data[i:i + step]); await asyncio.sleep(SLOW["ms"] / 10000)
    await resp.write_eof()
    return resp

async def history(request):
    q = request.query
    if q.get("scope", "app") not in ("app", "owner"): return err(400, "bad_params", "scope")
    return web.json_response(history_page(q.get("before"), q.get("limit", 50), q.get("scope", "app")), dumps=lambda o: json.dumps(o, ensure_ascii=False))

async def upload(request):
    data = await request.read()
    if not data: return err(400, "bad_file", "빈 파일")
    if len(data) > 31457280: return err(413, "too_large", "너무 큼")
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", request.query.get("name", "file"))[:100] or "file"
    print(f"[http] upload {name} {len(data)}B")
    return web.json_response(store_file(name, data), status=201, dumps=lambda o: json.dumps(o, ensure_ascii=False))

async def download(request):
    f = state["files"].get(request.match_info["id"])
    if f is None: return err(404, "not_found", "없는 파일")
    name, data, mime, gone = f
    if gone: return err(410, "gone", "보관 기간이 지나 지웠다")
    if FAIL["n"] > 0:
        # 잠깐의 실패 흉내(/_dev/fail?n=): 앱이 "다시 시도"를 어떻게 보이는지 볼 때 쓴다.
        FAIL["n"] -= 1
        return err(503, "unavailable", "잠시 뒤에 다시 해 주세요")
    print(f"[http] download {request.match_info['id']} {name} {len(data)}B")
    from urllib.parse import quote
    headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}", "X-Content-Type-Options": "nosniff", "Cache-Control": "private, no-store"}
    if not SLOW["ms"]:
        return web.Response(body=data, content_type=mime, headers=headers)
    # 느린 연결 흉내(/_dev/slow?ms=): 열 조각으로 나눠 그 시간 동안 보낸다. 앱이 받는 중을 어떻게 보이는지 볼 때 쓴다.
    resp = web.StreamResponse(headers={**headers, "Content-Type": mime, "Content-Length": str(len(data))})
    await resp.prepare(request)
    step = max(1, len(data) // 10)
    for i in range(0, len(data), step):
        await resp.write(data[i:i + step]); await asyncio.sleep(SLOW["ms"] / 10000)
    await resp.write_eof()
    return resp

async def put_push(request):
    body = await request.json()
    state["push"] = body.get("token"); print("[http] push registered")
    return web.json_response({"registered": True, "available": True})

async def del_push(_):
    state["push"] = None
    return web.json_response({"registered": False})


# ---------------------------------------------------------------- 시험용 입구

async def dev_send(request):
    kind = request.query.get("kind", "text"); body = request.query.get("body", "시험 메시지예요.")
    if kind == "card":
        ref, c = new_card_frame("mail"); push_frame({"type": "card", "ref": ref, "card": c})
    elif kind == "sticker":
        if request.query.get("id"):  # 목록에 없는 ID를 그대로 보내 보는 시험
            push_frame({"type": "sticker", "sticker": {"id": request.query["id"], "name": request.query.get("name", "?")}})
        else:
            send_sticker(request.query.get("name", "축하"))
    else:
        push_frame({"type": kind, "body": body})
    return web.json_response({"seq": state["seq"], "online": state["ws"] is not None})

async def dev_invoke(request):
    """/_dev/invoke?method=location.get&params={"precise":true} : 루시아가 앱 기능을 부른 것처럼 하고 앱의 답을 그대로 돌려준다."""
    method = request.query.get("method", "")
    try:
        params = json.loads(request.query.get("params") or "null")
    except ValueError:
        return web.json_response({"error": "params는 JSON이어야 한다"}, status=400)
    res = await call_app(method, params)
    return web.json_response(res, dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def dev_slow(request):
    SLOW["ms"] = int(request.query.get("ms", "0"))
    return web.json_response({"ms": SLOW["ms"]})

async def dev_fail(request):
    FAIL["n"] = int(request.query.get("n", "1"))
    return web.json_response({"n": FAIL["n"]})

async def dev_compact(request):
    """/_dev/compact?ms=5000&outcome=small&error=failed&busy=2&done=0 : 대화 정리의 걸리는 시간·결과·오류·busy 횟수를 정한다."""
    q = request.query
    if "ms" in q: COMPACT["ms"] = int(q["ms"])
    if "outcome" in q: COMPACT["outcome"] = q["outcome"]
    if "error" in q: COMPACT["error"] = q["error"] or None
    if "busy" in q: COMPACT["busy"] = int(q["busy"])
    if "done" in q: COMPACT["done"] = q["done"] == "1"
    return web.json_response(COMPACT)

async def dev_model(request):
    """/_dev/model?set=opus : 다른 곳(관리 페이지, Discord)에서 주 모델을 바꾼 것처럼 한다. 앱에는 아무 프레임도 가지 않는다."""
    if "set" in request.query: state["model"] = request.query["set"]
    return web.json_response({"model": state["model"]})

async def dev_drop(_):
    if state["ws"] is not None: await state["ws"].close()
    return web.Response(text="dropped")

async def dev_reset(request):
    reset(empty=bool(request.query.get("empty"))); return web.Response(text="reset")

async def dev_expressions(request):
    """목록을 바꾸고 연결된 앱에 번호 없는 `expressions` 프레임을 보낸다(3.3)."""
    q = request.query
    if q.get("restore"): expr["removed"].clear()
    if q.get("remove"): expr["removed"].add(q["remove"])
    version = catalog()["version"]
    push_frame({"type": "expressions", "version": version}, numbered=False)
    return web.json_response({"version": version, "removed": sorted(expr["removed"])})

async def dev_profile(request):
    """/_dev/profile?set=1|2|big|photo|wide|bad|huge|gone|none[&quiet=1] : 루시아가 프로필 그림을 바꾼 것처럼."""
    avatar = set_avatar(request.query.get("set", "1"), quiet=bool(request.query.get("quiet")))
    return web.json_response({"avatar": avatar})

async def dev_state(_):
    return web.json_response({"seq": state["seq"], "acked": state["acked"], "presence": state["presence"],
                              "online": state["ws"] is not None and not state["ws"].closed, "push": state["push"] is not None})


def make_app():
    app = web.Application(middlewares=[guard], client_max_size=64 * 1024 * 1024)
    app.add_routes([
        web.get("/v1/health", health), web.get("/v1/me", me), web.get("/v1/history", history),
        web.post("/v1/files", upload), web.get("/v1/files/{id}", download),
        web.put("/v1/push", put_push), web.delete("/v1/push", del_push), web.get("/v1/ws", ws_handler),
        web.get("/v1/expressions", expressions), web.get("/v1/expressions/files/{id}", expression_file),
        web.get("/v1/profile", profile), web.get("/v1/profile/avatar/{id}", profile_avatar), web.get("/_dev/profile", dev_profile),
        web.get("/v1/browser/handover/frame/{tab}", hand_frame), web.get("/_dev/handover", dev_handover),
        web.get("/_dev/expressions", dev_expressions), web.get("/_dev/slow", dev_slow), web.get("/_dev/fail", dev_fail), web.get("/_dev/compact", dev_compact), web.get("/_dev/model", dev_model), web.get("/_dev/invoke", dev_invoke),
        web.get("/_dev/send", dev_send), web.get("/_dev/drop", dev_drop), web.get("/_dev/reset", dev_reset), web.get("/_dev/state", dev_state),
    ])
    return app


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("--port", type=int, default=8790)
    p.add_argument("--scopes", default="", help="쉼표로 나눈 토큰 범위(예: security_mode)")
    p.add_argument("--no-expressions", action="store_true", help="이모지·스티커를 다루지 않는 데몬처럼 군다")
    p.add_argument("--legacy-commands", action="store_true", help="주 모델·current·session.compact가 없는 예전 데몬처럼 군다")
    p.add_argument("--no-profile", action="store_true", help="프로필 이미지 기능이 없는 예전 데몬처럼 군다")
    p.add_argument("--no-forms", action="store_true", help="입력 칸이 있는 카드(form)를 모르는 예전 데몬처럼 군다(capabilities.forms가 없다)")
    a = p.parse_args()
    SCOPES.extend(x for x in a.scopes.split(",") if x)
    EXPRESSIONS_ON = not a.no_expressions
    LEGACY_COMMANDS = a.legacy_commands
    PROFILE_ON = not a.no_profile
    FORMS_ON = not a.no_forms
    load_expressions()
    reset()
    print(f"가짜 Lucia 서버: 0.0.0.0:{a.port}\n토큰: {TOKEN}")
    web.run_app(make_app(), host="0.0.0.0", port=a.port, print=None)
