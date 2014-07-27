package com.mycompany.vp.testdocumentsdownload;

import android.provider.DocumentsContract;

/**
 * Created by vp on 7/27/2014.
 */
public class DocumentListItem {
    private final String documentName;
    private final String documentUri;

    public DocumentListItem(String name, String uri){
        documentName = name;
        documentUri = uri;
    }

    public String getUri(){
        return documentUri;
    }

    @Override
    public String toString() {
        return documentName;
    }
}
