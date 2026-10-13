import json, os, sys, glob, statistics as st
root=sys.argv[1]
tasks=["AudioCapsT2ARetrieval","ClothoT2ARetrieval","MusicCapsT2ARetrieval","MACST2ARetrieval","UrbanSound8KT2ARetrieval","EmoVDBT2ARetrieval","SoundDescsT2ARetrieval","AudioSetStrongT2ARetrieval"]
models=["laion__clap-htsat-fused","laion__clap-htsat-unfused","laion__larger_clap_general","laion__larger_clap_music","laion__larger_clap_music_and_speech","microsoft__msclap-2023","OpenMuQ__MuQ-MuLan-large","facebook__pe-av-small","facebook__pe-av-base","facebook__pe-av-large","Qwen__Qwen3-VL-Embedding-8B","nvidia__omni-embed-nemotron-3b","jinaai__jina-embeddings-v5-omni-nano","jinaai__jina-embeddings-v5-omni-small","google__gemini-embedding-2"]
def sc(p):
    d=json.load(open(p)); 
    for split,l in d["scores"].items():
        return st.mean(s["main_score"] for s in l)*100
print(f"{'model':40s}"+"".join(f"{t.replace('T2ARetrieval','')[:11]:>12s}" for t in tasks))
for m in models:
    revs=glob.glob(os.path.join(root,"results",m,"*"))
    best={}
    for r in revs:
        b={t:sc(os.path.join(r,t+".json")) for t in tasks if os.path.exists(os.path.join(r,t+".json"))}
        if len(b)>len(best): best=b
    print(f"{m[:40]:40s}"+"".join(f"{best[t]:12.2f}" if t in best else f"{'-':>12s}" for t in tasks))
