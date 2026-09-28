import json, sys, time, urllib.request, datetime as dt, pathlib
home = pathlib.Path.home()
props = dict(l.strip().split("=",1) for l in open(home/".topstep"/"credentials.properties") if "=" in l and not l.startswith("#"))
api = props["apiUrl"].strip().rstrip("/"); api = api if api.endswith("/api") else api+"/api"
def post(path, body, token=None):
    req = urllib.request.Request(api+path, data=json.dumps(body).encode(), headers={"Content-Type":"application/json", **({"Authorization":"Bearer "+token} if token else {})})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code==429: time.sleep(2*(attempt+1)); continue
            raise
    raise RuntimeError("429 forever")
auth = post("/Auth/loginKey", {"userName":props["username"].strip(), "apiKey":props["apiKey"].strip()})
tok = auth.get("token"); assert tok, auth
days = int(sys.argv[1]) if len(sys.argv)>1 else 21
out = pathlib.Path(sys.argv[2]) if len(sys.argv)>2 else home/"topstep-trading"/"tape"
for sym in ["MNQ","MES"]:
    cid=None
    for live in (False, True):
        cs = post("/Contract/search", {"searchText":sym,"live":live}, tok).get("contracts") or []
        ex = [c for c in cs if c["id"].split(".")[3]==sym]
        act = [c for c in ex if c.get("activeContract")]
        if act or ex: cid=(act or ex)[0]["id"]; break
    print(sym, "contract", cid, flush=True)
    end = dt.datetime.now(dt.timezone.utc).replace(second=0,microsecond=0)
    cur = end - dt.timedelta(days=days)
    bars={}
    while cur < end:
        ce = min(cur+dt.timedelta(hours=6), end)
        r = post("/History/retrieveBars", {"contractId":cid,"live":False,"startTime":cur.isoformat().replace("+00:00","Z"),"endTime":ce.isoformat().replace("+00:00","Z"),"unit":2,"unitNumber":1,"limit":1000,"includePartialBar":False}, tok)
        if not r.get("success", True): print("ERR", r, flush=True)
        for b in r.get("bars") or []:
            bars[b["t"]] = {"t":b["t"],"o":b["o"],"h":b["h"],"l":b["l"],"c":b["c"],"v":b.get("v",0)}
        cur = ce; time.sleep(0.25)
    arr = [bars[k] for k in sorted(bars)]
    (out/f"real_{sym}_1m.json").write_text(json.dumps(arr))
    print(sym, "bars", len(arr), arr[0]["t"] if arr else None, arr[-1]["t"] if arr else None, flush=True)
