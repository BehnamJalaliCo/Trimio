import json, os, sys, glob, statistics as st
sys.path.insert(0, os.path.dirname(__file__))
root=sys.argv[1]
tasks=["MassiveIntentClassification","MassiveScenarioClassification","MIRACLReranking","WikipediaRerankingMultilingual","WikipediaRetrievalMultilingual","MIRACLRetrievalHardNegatives","NeuCLIR2023RetrievalHardNegatives","WebFAQRetrieval","SIDClassification.v2","SynPerQARetrieval","PersianWebDocumentRetrieval"]
def score(path):
    d=json.load(open(path)); vals=[]
    for split,lst in d.get("scores",{}).items():
        for s in lst:
            if any(l.startswith("fas") for l in s.get("languages",[])): vals.append(s["main_score"])
        if vals: return st.mean(vals)*100
out={}
for mdir in sorted(glob.glob(os.path.join(root,"results","*"))):
    best={}
    for rev in glob.glob(os.path.join(mdir,"*")):
        r={}
        for t in tasks:
            p=os.path.join(rev,t+".json")
            if os.path.exists(p):
                try:
                    v=score(p)
                    if v is not None: r[t]=v
                except: pass
        if len(r)>len(best): best=r
    out[os.path.basename(mdir)]=best
print(f"{'model':48s}"+"".join(f"{t[:10]:>11s}" for t in tasks))
for m,b in out.items():
    print(f"{m[:48]:48s}"+"".join(f"{b[t]:11.2f}" if t in b else f"{'-':>11s}" for t in tasks))
