import json, os, sys, glob, statistics as st
root = sys.argv[1]
cats = {
 "Cls": ["PersianFoodSentimentClassification","SynPerChatbotConvSAClassification","SynPerChatbotConvSAToneChatbotClassification","SynPerChatbotConvSAToneUserClassification","SynPerChatbotSatisfactionLevelClassification","SynPerTextToneClassification.v3","SIDClassification.v2","DeepSentiPers.v2","PersianTextEmotion.v2","NLPTwitterAnalysisClassification.v2","DigikalamagClassification","MassiveIntentClassification","MassiveScenarioClassification","StyleClassification","PerShopDomainClassification","PerShopIntentClassification"],
 "Clust": ["BeytooteClustering","DigikalamagClustering","HamshahriClustring","NLPTwitterAnalysisClustering","SIDClustring"],
 "PairCls": ["FarsTail","SynPerChatbotRAGFAQPC","FarsiParaphraseDetection","SynPerTextKeywordsPC","SynPerQAPC","ParsinluEntail","ParsinluQueryParaphPC"],
 "Rerank": ["MIRACLReranking","WikipediaRerankingMultilingual"],
 "Retr": ["SynPerQARetrieval","SynPerChatbotRAGFAQRetrieval","PersianWebDocumentRetrieval","WikipediaRetrievalMultilingual","MIRACLRetrievalHardNegatives","HotpotQA-FaHardNegatives","MSMARCO-FaHardNegatives","NQ-FaHardNegatives","ArguAna-Fa.v2","FiQA2018-Fa.v2","QuoraRetrieval-Fa.v2","SCIDOCS-Fa.v2","SciFact-Fa.v2","TRECCOVID-Fa.v2","FEVER-FaHardNegatives","NeuCLIR2023RetrievalHardNegatives","WebFAQRetrieval"],
 "STS": ["Farsick","SynPerSTS"],
 "SumRet": ["SAMSumFa","SynPerChatbotSumSRetrieval","SynPerChatbotRAGSumSRetrieval"],
}
def score(path):
    d = json.load(open(path))
    vals=[]
    for split, lst in d.get("scores",{}).items():
        for s in lst:
            langs = s.get("languages",[])
            if any(l.startswith("fas") for l in langs):
                vals.append(s["main_score"])
        if vals: return st.mean(vals)
    return None
rows=[]
for mdir in sorted(glob.glob(os.path.join(root,"results","*"))):
    best=None
    for rev in glob.glob(os.path.join(mdir,"*")):
        res={}
        for c,ts in cats.items():
            for t in ts:
                p=os.path.join(rev,t+".json")
                if os.path.exists(p):
                    try:
                        v=score(p)
                    except Exception as e: v=None
                    if v is not None: res[t]=(c,v)
        if best is None or len(res)>len(best): best=res
    if not best: continue
    catm={c: st.mean([v for (cc,v) in best.values() if cc==c])*100 for c in cats if any(cc==c for (cc,_) in best.values())}
    n=len(best)
    rows.append((os.path.basename(mdir), n, catm, st.mean([v for (_,v) in best.values()])*100))
tot=sum(len(v) for v in cats.values())
print(f"{'model':55s} n/{tot} TaskMean TypeMean  "+"  ".join(f'{c:>7s}' for c in cats))
for name,n,catm,mean in sorted(rows,key=lambda r:-r[3]):
    tm = st.mean(catm.values())
    print(f"{name:55s} {n:3d} {mean:7.2f} {tm:7.2f}  "+"  ".join(f"{catm.get(c,float('nan')):7.2f}" for c in cats))
