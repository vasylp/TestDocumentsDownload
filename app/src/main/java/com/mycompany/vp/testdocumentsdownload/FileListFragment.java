package com.mycompany.vp.testdocumentsdownload;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
import android.widget.Toast;

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
    private BroadcastReceiver receiver;

    private ArrayAdapter<DocumentListItem> fileListAdapter;

    public FileListFragment(){
        super();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onDestroyView() {
        getActivity().unregisterReceiver(receiver);
        super.onDestroyView();
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

        receiver = new BroadcastReceiver() {
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
                            String remoteUriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_URI));

                            File destinationFile = null;

                            try{
                                destinationFile = writeToCache(new File(localUri), Uri.parse(remoteUriString));
                            }catch (IOException e){
                                Log.e(LOG_TAG, "Fail to cache file [" + localUri + "]");
                            }

                            if (destinationFile != null){
                                startViewer(destinationFile);
                            }
                        }
                    }
                }
            }

            File writeToCache(File sourceFile, Uri remoteUri) throws IOException{
                File destinationFile = new File(getActivity().getCacheDir(), remoteUri.getLastPathSegment());
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

        };

        getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        return rootView;
    }

    private void startViewer(File documentName){
        String fileType = mimeType(documentName);
        Uri fileUri = FileProvider.getUriForFile(getActivity(), getActivity().getPackageName(), documentName);

        Intent viewIntent = new Intent();
        viewIntent.setAction(Intent.ACTION_VIEW);
        viewIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewIntent.setDataAndType(fileUri, fileType);
        getActivity().startActivity(viewIntent);
    }

    String mimeType(File file){
        String filename = file.getName();
        String extension = filename.substring(filename.lastIndexOf(".") + 1);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    private void downloadDocumentList() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String rootUri = prefs.getString(getString(R.string.key_documents_uri),
                getString(R.string.file_list_uri));
        FetchDocumentList task = new FetchDocumentList(fileListAdapter);
        task.execute(rootUri);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {

        ArrayAdapter<DocumentListItem> adapter = (ArrayAdapter<DocumentListItem>)adapterView.getAdapter();
        DocumentListItem item = adapter.getItem((int) l);

        // get the filename and check whether we have a cached copy of it
        Uri documentUri = Uri.parse(item.getUri());

        File cachedDocumentUri = new File(getActivity().getCacheDir(), documentUri.getLastPathSegment());
        if(cachedDocumentUri.exists()){
            Log.d(LOG_TAG, "File [" + documentUri.getLastPathSegment() + "] exists. Using cached copy");
            Toast.makeText(getActivity(), "Opening cached document", Toast.LENGTH_SHORT).show();
            startViewer(cachedDocumentUri);
        } else {
            Log.d(LOG_TAG, "Downloading [" + documentUri.toString());
            // downloading using download manager
            Toast.makeText(getActivity(), "Downloading document", Toast.LENGTH_SHORT).show();
            dm = (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(documentUri);
            enqueue = dm.enqueue(request);
        }
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
                urlConnection.setInstanceFollowRedirects(true);
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                if(urlConnection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM){
                    String newUrl = urlConnection.getHeaderField("Location");
                    urlConnection.disconnect();
                    urlConnection = (HttpURLConnection)new URL(newUrl).openConnection();
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setRequestMethod("GET");
                    urlConnection.connect();
                }

                if(urlConnection.getResponseCode() != HttpURLConnection.HTTP_OK){
                    Log.e(LOG_TAG, "HTTP return: " + urlConnection.getResponseMessage());
                    return null;
                }


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
            if(strings == null){
                return;
            }
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
