/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bhvr.android.auto;

import android.media.MediaMetadata;
import android.os.AsyncTask;
import android.support.v4.media.MediaMetadataCompat;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Utility class to get a list of MusicTrack's based on a server-side JSON
 * configuration.
 */
public class StationsProvider {

    private static final String CATALOG_URL =
        "https://api.cogecolive.com/stations?with=streams,images";

    private static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";

    private static final String JSON_MUSIC = "data";
    private static final String JSON_TITLE = "title";
    private static final String JSON_SOURCE = "source";
    private static final String JSON_IMAGE = "image";

    private ConcurrentMap<String, List<MediaMetadataCompat>> mStationLists;
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;


    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public StationsProvider() {
        mMusicListById = new ConcurrentHashMap<>();
    }

    /**
     * Get music tracks of the given genre
     */

   public ConcurrentMap<String, List<MediaMetadataCompat>> getStationsList() {
        if (mCurrentState != State.INITIALIZED) {
            return new ConcurrentHashMap<>();
        }
        return mStationLists;
   }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        if (mCurrentState == State.INITIALIZED) {
            // Nothing to do, execute callback immediately
            callback.onMusicCatalogReady(true);
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveMedia();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    private synchronized void buildListById() {
        ConcurrentMap<String, List<MediaMetadataCompat>> stationsList = new ConcurrentHashMap<>();

        for (MutableMediaMetadata m : mMusicListById.values()) {
            String id = m.metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            List<MediaMetadataCompat> list = stationsList.get(id);
            if (list == null) {
                list = new ArrayList<>();
                stationsList.put(id, list);
            }
            list.add(m.metadata);
        }
        mStationLists = stationsList;
    }


    private synchronized void retrieveMedia() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                int slashPos = CATALOG_URL.lastIndexOf('/');
                String path = CATALOG_URL.substring(0, slashPos + 1);
                JSONObject jsonObj = fetchJSONFromUrl(CATALOG_URL);
                if (jsonObj == null) {
                    return;
                }
                JSONArray tracks = jsonObj.getJSONArray(JSON_MUSIC);
                if (tracks != null) {
                    for (int j = 0; j < tracks.length(); j++) {
                        MediaMetadataCompat item = buildFromJSON(tracks.getJSONObject(j), path);
                        String musicId = item.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                        mMusicListById.put(musicId, new MutableMediaMetadata(musicId, item));
                    }
                    buildListById();

                }
                mCurrentState = State.INITIALIZED;
            }
        } catch (JSONException e) {
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }


    private MediaMetadataCompat buildFromJSON(JSONObject json, String basePath) throws JSONException {

        String title = json.getString("name");

        JSONObject images = json.getJSONObject("images");
        JSONObject image = images.getJSONObject("logo");
        JSONObject coloredImages = images.getJSONObject("logo");

        JSONArray streams = json.getJSONArray("streams");
        JSONObject stream = streams.getJSONObject(0);

        String source = stream.getString("url");
        String iconUrl = image.getString("ori");

        if (!source.startsWith("http")) {
            source = basePath + source;
        }

        if (!iconUrl.startsWith("http")) {
            iconUrl = basePath + iconUrl;
        }

        String id = String.valueOf(json.getInt("id"));


        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, source)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, iconUrl)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .build();
    }

    /**
     * Download a JSON file from a server, parse the content and return the JSON
     * object.
     *
     * @return result JSONObject containing the parsed representation.
     */
    private JSONObject fetchJSONFromUrl(String urlString) {
        InputStream is = null;
        try {
            URL url = new URL(urlString);
            URLConnection urlConnection = url.openConnection();
            is = new BufferedInputStream(urlConnection.getInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    urlConnection.getInputStream(), "iso-8859-1"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
