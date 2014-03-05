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

/**
 *
 * @author andrew
 */
public class SuggesterComponentParams {

    
    public static final long serialVersionUID = 42L;
    public static String PRODUCT_NAME = "sbsuggester";
    public static String PRODUCT_KEY = "sug";
    public static final String COMPONENT_NAME = "sbsuggester";
    public static final String STOP_WORD_LOCATION = "stopwordLocation";
    public static final String STOP_WORD_LOCATION_DEFAULT = "/stopwords_for_suggestor.txt";
    public static final String BUILD_ON_OPTIMIZE = "buildOnOptimize";
    public static final String BUILD_ON_OPTIMIZE_DEFAULT = "false";
    public static final String BUILD_ON_COMMIT = "buildOnCommit";
    public static final String BUILD_ON_COMMIT_DEFAULT = "false";
    public static final String FIELDS = "fields";
    public static final String FIELD = "field";
    public static final String STOREDIR = "storeDir";
    public static final String STOREDIR_DEFAULT = COMPONENT_NAME;
    public static final String NONPRUNEFILE = "nonPruneFile";
    public static final String NGRAMS = "ngrams";
    public static final String NGRAMS_DEFAULT = "3";
    public static final String BUILD = "build";
    public static final String QUERY = "q";
    public static final String COUNT = "count";
    public static final Integer COUNT_DEFAULT = 5;
    public static final String MINDOCFREQ = "minDocFreq";
    public static final Integer MINDOCFREQ_DEFAULT = 2;
    public static final String MINTERMFREQ = "minTermFreq";
    public static final Integer MINTERMFREQ_DEFAULT = 2;
    public static final String MAX_PHRASE_SEARCH = "mps";
    public static final Integer MAX_PHRASE_SEARCH_DEFAULT = 100;
    public static final String MAXNUMDOCS = "maxNumDocs";
    public static final Integer MAXNUMDOCS_DEFAULT = -1;
    
}
