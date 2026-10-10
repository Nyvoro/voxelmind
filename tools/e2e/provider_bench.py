"""Measures OpenRouter providers for the companion model with a real logged prompt and tool_choice=required."""
import json, os, time, urllib.request, statistics, sys

KEY = os.environ['OPENROUTER_API_KEY']
MODEL = 'deepseek/deepseek-v4-flash-0731'
LOG = 'V:/VoxelMind/voxelmind-local/run/world/voxelmind-local/logs/Lysa.jsonl'
prompt = None
for line in open(LOG, encoding='utf-8'):
    r = json.loads(line)
    if r.get('lane') in ('action', 'decision') and r.get('prompt'):
        prompt = r['prompt']
tools = [
    {"type": "function", "function": {"name": n, "description": d, "parameters": {"type": "object", "properties": {"target": {"type": "string"}}, "required": []}}}
    for n, d in [("go_to", "Walk to a target."), ("gather", "Collect a resource."), ("follow", "Follow someone."), ("wait", "Wait."), ("keep_current_action", "Keep it.")]
]
providers = sys.argv[1:] or ["DeepInfra", "StreamLake", "Wafer", "CoreWeave", "Relace", "Reka", "Inceptron", "Together", "BaseTen"]
for prov in providers:
    lat, ok, cost, errs = [], 0, 0.0, []
    for i in range(5):
        body = {"model": MODEL, "messages": [{"role": "system", "content": "You are an AI companion in Minecraft."}, {"role": "user", "content": prompt + "\n[ACTION DECISION]\nCall exactly one offered action tool."}],
                "tools": tools, "tool_choice": "required", "temperature": 0.7, "max_tokens": 512, "reasoning": {"enabled": False}, "usage": {"include": True},
                "provider": {"order": [prov], "allow_fallbacks": False}}
        req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
        t = time.time()
        try:
            with urllib.request.urlopen(req, timeout=40) as resp:
                d = json.loads(resp.read())
            dt = time.time() - t
            calls = d['choices'][0]['message'].get('tool_calls') or []
            if calls: ok += 1
            else: errs.append('no tool call')
            lat.append(dt)
            cost += (d.get('usage') or {}).get('cost') or 0
        except Exception as e:
            errs.append(str(e)[:60])
            lat.append(time.time() - t)
    lat.sort()
    print(f"{prov:12} ok {ok}/5  p50 {statistics.median(lat):.1f}s  max {lat[-1]:.1f}s  cost/call ${cost/max(1,ok):.5f}  {errs[:2]}")
