package com.searchbox.searchboxsuggester;

import java.io.Serializable;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author andrew
 */
public class TrieNode implements Serializable{

    private static Logger LOGGER = LoggerFactory.getLogger(TrieNode.class);
    HashMap<Character, TrieNode> children = new HashMap<Character, TrieNode>();
    HashMap<String, Double> phrases = new HashMap<String, Double>();
    public long termfreq = 0;
    public long docfreq = 0;
    NormalizeCount nc;
    private int ngram;
    private int numdocs;
    String myval;

    public TrieNode(String prefix, int NGRAM) {
        this.myval = prefix;
        nc = new NormalizeCount(NGRAM);
        ngram=NGRAM;
    }

    public boolean containsChildValue(char c) {
        return children.containsKey(c);
    }

    public TrieNode getChild(char c) {
        return children.get(c);
    }

    public TrieNode AddString(String val) {
        TrieNode head = this;
        for (char c : val.toCharArray()) {
            if (head.containsChildValue(c)) {
                head = head.getChild(c);
            } else {
                TrieNode temp = this;
                temp = new TrieNode(head.myval + c,ngram);
                head.children.put(c, temp);
                head = temp;
            }
        }
        return head;
    }

    public NormalizeCount computeNormalizeTerm(int level, int numdocs) {
        // Logger.info("level\t"+level);
        if(docfreq>0){
            this.numdocs=numdocs;
            nc.termnormfactor += termfreq * Math.log10((numdocs*1.0)/docfreq);
        }
        doPhraseTotalFreq();
        
        for (TrieNode child : children.values()) {
            NormalizeCount lnc = child.computeNormalizeTerm(level + 1,numdocs);
            nc.add(lnc);

        }
        return nc;
    }


    
    /*public void printRecurse(){
        Logger.info(myval+"\t"+this.termfreq+"\t"+this.docfreq+"\t"+this.phrases.size()+"\t"+nc.termnormfactor);
        for (String p :phrases.keySet()){
            Logger.info("\t"+p+"\t"+phrases.get(p)+"\t"+nc.phrasenormfactor);
        }
        for (TrieNode child : children.values()) {
            child.printRecurse();
        }
        
    }*/
    
    private void doPhraseTotalFreq() {
        for (Double f : phrases.values()) {
            int gram=(int) (Math.round((f-Math.floor(f))*10.0)-1); // check right side of decimal to get ngram
            nc.ngramfreq[gram]+=Math.floor(f);
            nc.ngramnum[gram]++;
        }
    }

    SuggestionResultSet computeQt(String partialToken) {
        TrieNode current = this;
        for (char c : partialToken.toCharArray()) {
            if (current.containsChildValue(c)) {
                current = current.getChild(c);
            } else {
                break;
            }
        }
        return current.recurse(current.nc.termnormfactor);
    }

    private SuggestionResultSet recurse(double termnormfactor) {
        
        
        SuggestionResultSet srs = new SuggestionResultSet(myval);
        if (phrases.size() > 0) { //this term exists as a whole
            double p_ci_qt= (      termfreq*Math.log10((1.0*numdocs)/(docfreq))     ) / (   termnormfactor );
            srs.add(phrases,p_ci_qt,nc.phrasenormfactor);//add his phrases and whatnot            
        }
        for (TrieNode child : children.values()) { //this term also exists as a partial
            srs.add(child.recurse(termnormfactor));
        }
        return srs;
    }

    void computeNormalizePhrase(double[] logavgfreq) {
        for (String phrase : phrases.keySet()) {
            //Logger.info(phrase + "\t" + phrases.get(phrase));
            
            double val =  phrases.get(phrase);
            int gram=(int)Math.round((val-Math.floor(val))*10.0)-1;
            double newval = Math.floor(val) / logavgfreq[gram];
            nc.phrasenormfactor += newval;
            phrases.put(phrase, newval);
        }
        
        for (TrieNode child : children.values()) {
            child.computeNormalizePhrase(logavgfreq);
        }
        //return nc;
    }



    public class NormalizeCount implements Serializable{

        public double termnormfactor = 0;
        public double phrasenormfactor = 0;
        public double ngramnum[];
        public double ngramfreq[];
        
        public NormalizeCount(int NGRAM){
              ngramnum= new double[NGRAM];
              ngramfreq = new double[NGRAM];
        }
        
        public void add(NormalizeCount in) {
            this.termnormfactor += in.termnormfactor;
            for(int zz=0;zz<ngramnum.length;zz++) {
                ngramfreq[zz]+=in.ngramfreq[zz];
                ngramnum[zz]+=in.ngramnum[zz];
            }
        }
    }
}
