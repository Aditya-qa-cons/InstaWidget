#!/usr/bin/env python3
"""
Checks DmStore's conversation-merging rules without a device.

This is a faithful port of DmStore.add and DmStore.resetCounts. The real
versions are entangled with Context, SharedPreferences and org.json, so they
cannot run on a plain JVM; the merge semantics are the part worth testing, and
they are pure. Keep this in step with DmStore.kt when those rules change.

    python3 tools/grouping-check.py
"""
MAX = 25

def key(m):
    # Scoped by account: the same person messaging two logged-in Instagram
    # accounts is two conversations, not one.
    return (m.get("account") or "").strip().lower() + "\0" + m["sender"].strip().lower()

def add(cur, msg):
    k = key(msg)
    existing = next((e for e in cur if key(e) == k), None)
    if existing is None:
        merged = dict(msg)
    elif existing["preview"] == msg["preview"]:
        already = existing["postedAt"] == msg["postedAt"] and cur and key(cur[0]) == k
        if already:
            return cur, False
        merged = dict(existing); merged["postedAt"] = max(existing["postedAt"], msg["postedAt"])
    else:
        merged = dict(msg); merged["count"] = existing["count"] + 1
    out = [merged]
    for e in cur:
        if key(e) == k: continue
        out.append(e)
        if len(out) >= MAX: break
    return out, True

def reset(cur):
    if not any(e["count"] > 1 for e in cur): return cur, False
    return [dict(e, count=1) for e in cur], True

def m(s, p, t, c=1, account=None):
    return {"sender": s, "preview": p, "postedAt": t, "count": c, "account": account}

def show(label, cur):
    print(f"{label}:")
    for e in cur:
        badge = f"  [{'9+' if e['count']>9 else e['count']}]" if e["count"] > 1 else ""
        print(f"   {e['sender']:<26} {e['preview']:<12} t={e['postedAt']}{badge}")
    print()

cur = []
for msg in [m("Girlwithabrokenbong","Hiiiii",100),
            m("Girlwithabrokenbong","Testing",200),
            m("Girlwithabrokenbong","Again",300),
            m("Cozy Cat Kitchen","Testing",310)]:
    cur, _ = add(cur, msg)
show("After the screenshot's four notifications", cur)
assert len(cur) == 2, cur
assert cur[0]["sender"] == "Cozy Cat Kitchen" and cur[0]["count"] == 1
assert cur[1]["sender"] == "Girlwithabrokenbong" and cur[1]["count"] == 3
assert cur[1]["preview"] == "Again"

# MessagingStyle re-posts the same newest message repeatedly.
before = [dict(e) for e in cur]
cur, changed = add(cur, m("Girlwithabrokenbong","Again",300))
cur, _ = add(cur, m("Cozy Cat Kitchen","Testing",310))
show("After identical re-posts", cur)
assert max(e["count"] for e in cur) == 3, "re-post must not inflate the count"

# Casing / whitespace must not split a conversation.
cur, _ = add(cur, m(" girlwithabrokenbong ","new one",400))
show("After a differently-cased sender", cur)
assert len(cur) == 2, "casing split the conversation"
assert cur[0]["count"] == 4

# Tapping the widget clears the counts but keeps the previews.
cur, changed = reset(cur)
show("After opening the inbox", cur)
assert changed and all(e["count"] == 1 for e in cur)
assert cur[0]["preview"] == "new one"
_, changed = reset(cur)
assert not changed, "reset must be idempotent"

# The same sender writing to two different logged-in accounts.
cur = []
for msg in [m("priya", "hi there", 100, account="work.acct"),
            m("priya", "and again", 200, account="work.acct"),
            m("priya", "different account", 300, account="personal.acct")]:
    cur, _ = add(cur, msg)
show("Same sender, two accounts", cur)
assert len(cur) == 2, "accounts must not be merged into one row"
assert cur[0]["account"] == "personal.acct" and cur[0]["count"] == 1
assert cur[1]["account"] == "work.acct" and cur[1]["count"] == 2

print("all grouping assertions passed")
