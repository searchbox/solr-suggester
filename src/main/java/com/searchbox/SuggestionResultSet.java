/*******************************************************************************
 * Copyright Searchbox - http://www.searchbox.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.searchbox;

import java.util.HashMap;
import java.util.TreeSet;

/**
 *
 * @author andrew
 */
class SuggestionResultSet{
    public TreeSet<SuggestionResult> suggestions;
    public String myval;
    private long maxTreeSize=100;
    

    SuggestionResultSet(String myval, int maxnumphrases) {
        this.myval=myval;
        suggestions=new TreeSet();
        maxTreeSize=maxnumphrases;
    }
    
    public void add(String phrase, double probability){
        suggestions.add(new SuggestionResult(phrase, probability));
        if(maxTreeSize!=-1){
        if(suggestions.size()>maxTreeSize){
            suggestions.remove(suggestions.last());
        }
        }
    }
    
    
    public void add(SuggestionResultSet in){
        if(in!=null){
            for(SuggestionResult sr : in.suggestions){
                add(sr.suggestion,sr.probability);
            }
        }
    }
    
    
    public void setNewval(SuggestionResult sr,Double newval){
        suggestions.remove(sr);
        sr.probability=newval;
        suggestions.add(sr);
    }

    void add(HashMap<String, Double> phrases, double p_ci_qt, double phrasenormfactor) {
        for (String phrase: phrases.keySet()){
            double p_pij_ci=phrases.get(phrase)/phrasenormfactor;
            double p_pij_qt=p_ci_qt*p_pij_ci;
            add(phrase,p_pij_qt);
        }
    }

    
    public class SuggestionResult implements Comparable<SuggestionResult>{
            String suggestion;
            Double probability;

        private SuggestionResult(String phrase, double p_pij_qt) {
            this.suggestion=phrase;
            this.probability=p_pij_qt;
        }

        public int compareTo(SuggestionResult o) {
            int retval=o.probability.compareTo(this.probability);
            if(retval==0){
               retval=o.suggestion.compareTo(this.suggestion);
            }
            
            return  retval;
        }
        
    }
}
