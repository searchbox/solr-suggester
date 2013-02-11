/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.searchboxsuggester;

/**
 *
 * @author andrew
 */
public class SuggesterComponentParams {

    public static final String COMPONENT_NAME = "sbsuggester";
    public static final String BUILD_ON_OPTIMIZE = "buildOnOptimize";
    public static final String BUILD_ON_OPTIMIZE_DEFAULT = "false";
    public static final String BUILD_ON_COMMIT = "buildOnCommit";
    public static final String BUILD_ON_COMMIT_DEFAULT = "false";
    public static final String FIELDS = "fields";
    public static final String FIELD = "field";
    public static final String STOREDIR = "storeDir";
    public static final String STOREDIR_DEFAULT = COMPONENT_NAME;
    public static final String NGRAMS = "ngrams";
    public static final String NGRAMS_DEFAULT = "3";
    public static final String BUILD = "build";
    
    
    public static final String QUERY = "q";
    public static final String COUNT = "count";
    public static final Integer COUNT_DEFAULT = 5;
}
