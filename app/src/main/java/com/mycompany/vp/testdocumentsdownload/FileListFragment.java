package com.mycompany.vp.testdocumentsdownload;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.v4.app.Fragment;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A placeholder fragment containing a simple view.
 */
public class FileListFragment extends Fragment implements AdapterView.OnItemClickListener{

    private final String LOG_TAG = FileListFragment.class.getSimpleName();

    private long enqueue;
    private DownloadManager dm;

    private String fileListJSONUri;

    private ArrayAdapter<DocumentListItem> fileListAdapter;

    public FileListFragment(){
        super();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileListJSONUri = getString(R.string.file_list_uri);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_documents_list, container, false);
        fileListAdapter = new ArrayAdapter<DocumentListItem>(getActivity(),
                R.layout.list_item_view,
                R.id.list_item_document_name);
        ListView list = (ListView) rootView.findViewById(R.id.documents_list);
        list.setAdapter(fileListAdapter);
        list.setOnItemClickListener(this);

        downloadDocumentList();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if(DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)){
                    long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(enqueue);
                    Cursor c = dm.query(query);
                    if(c.moveToFirst()){
                        int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if(c.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL){
                            String localUri = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));

                            File destinationFile = null;

                            try{
                                destinationFile = writeToCache(new File(localUri));
                            }catch (IOException e){
                                Log.e(LOG_TAG, "Fail to cache file [" + localUri + "]");
                            }

                            if (destinationFile != null){
                                String fileType = mimeType(destinationFile);
                                Uri fileUri = FileProvider.getUriForFile(getActivity(), getActivity().getPackageName(), destinationFile);

                                Intent viewIntent = new Intent();
                                viewIntent.setAction(Intent.ACTION_VIEW);
                                viewIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                viewIntent.setDataAndType(fileUri, fileType);
                                getActivity().startActivity(viewIntent);
                            }
                        }
                    }
                }
            }

            File writeToCache(File sourceFile) throws IOException{
                File destinationFile = new File(getActivity().getCacheDir(), sourceFile.getName());
                InputStream input = new FileInputStream(sourceFile);
                OutputStream output = new FileOutputStream(destinationFile);

                byte[] buf = new byte[1024];
                int len;
                while ((len = input.read(buf)) > 0) {
                    output.write(buf);
                }
                input.close();
                output.close();

                return destinationFile;
            }

            String mimeType(File file){
                String filename = file.getName();
                String extension = filename.substring(filename.lastIndexOf(".") + 1);
                return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
        };

        getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        return rootView;
    }

    private void downloadDocumentList() {
        FetchDocumentList task = new FetchDocumentList(fileListAdapter);
        task.execute(fileListJSONUri);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {

        ArrayAdapter<DocumentListItem> adapter = (ArrayAdapter<DocumentListItem>)adapterView.getAdapter();
        DocumentListItem item = adapter.getItem((int) l);

        // downloading using download manager
        dm = (DownloadManager)getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.getUri()));
        enqueue = dm.enqueue(request);
    }

    public class FetchDocumentList extends AsyncTask<String, Void, DocumentListItem[]> {

        private final String LOG_TAG =FetchDocumentList.class.getSimpleName();

        private final ArrayAdapter<DocumentListItem> documentsAdapter;

        FetchDocumentList(ArrayAdapter<DocumentListItem> adapter){
            documentsAdapter = adapter;
        }

        @Override
        protected DocumentListItem[] doInBackground(String... values) {

            if(values.length == 0){
                Log.e(LOG_TAG, "The URI is empty");
                return null;
            }

            String uri = values[0];


            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;

            String documentsJsonString = null;

            DocumentListItem[] documentsList = null;

            try{
                URL url = new URL(uri);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();

                if(inputStream != null){
                    reader = new BufferedReader(new InputStreamReader(inputStream));
                }

                if(reader != null){
                    String line;
                    while((line = reader.readLine()) != null){
                        buffer.append(line + "\n");
                    }
                    if(buffer.length() != 0){
                        documentsJsonString = buffer.toString();
                    }
                }

                if(documentsJsonString != null){
                    documentsList = parseDocumentsJSON(documentsJsonString);
                }

            }catch(IOException e){
                Log.e(LOG_TAG, "Error ", e);
            }catch(JSONException e){
                Log.e(LOG_TAG, "JSON Error ", e);
            }finally {
                if(urlConnection != null){
                    urlConnection.disconnect();
                }
                if(reader != null){
                    try{
                        reader.close();
                    }catch (final IOException e){
                        Log.e(LOG_TAG, "Error closing stream ", e);
                    }
                }
            }

            return documentsList;
        }

        @Override
        protected void onPostExecute(DocumentListItem[] strings) {
            documentsAdapter.clear();
            for(DocumentListItem item:strings){
                documentsAdapter.add(item);
            }
        }

        private DocumentListItem[] parseDocumentsJSON(String jsonString) throws JSONException {
            JSONObject documentsJSON = new JSONObject(jsonString);
            JSONArray documentsArray = documentsJSON.getJSONArray("list");
            DocumentListItem[] result = new DocumentListItem[documentsArray.length()];
            for(int i = 0; i < documentsArray.length(); ++i){
                JSONObject document = documentsArray.getJSONObject(i);
                result[i] = new DocumentListItem(document.getString("name"), document.getString("uri"));
            }
            return result;
        }
    }
}
