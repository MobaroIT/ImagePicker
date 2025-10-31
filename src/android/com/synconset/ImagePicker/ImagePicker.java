/**
 * An Image Picker Plugin for Cordova/PhoneGap.
 */
package com.synconset;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

import android.net.Uri;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.File;

public class ImagePicker extends CordovaPlugin {

    private static final String ACTION_GET_PICTURES = "getPictures";
    private static final String ACTION_HAS_READ_PERMISSION = "hasReadPermission";
    private static final String ACTION_REQUEST_READ_PERMISSION = "requestReadPermission";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_LEGACY_PICKER = 0;
    private static final int REQUEST_SYSTEM_PICKER = 0x1324;

    protected JSONArray args;
    private CallbackContext callbackContext;

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        if (ACTION_HAS_READ_PERMISSION.equals(action)) {
            // On Android 13+ we rely on system picker, so report "true"
            boolean has = hasReadPermission() || isSystemPickerCapable();
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, has));
            return true;

        } else if (ACTION_REQUEST_READ_PERMISSION.equals(action)) {
            // On Android 13+ we don't really need to request anything
            if (isSystemPickerCapable()) {
                callbackContext.success(1);
            } else {
                requestReadPermission();
            }
            return true;

        } else if (ACTION_GET_PICTURES.equals(action)) {
            this.args = args;

            if (isSystemPickerCapable()) {
                // Android 13+ → built-in picker, no permission
                launchSystemPhotoPicker();
            } else {
                // Pre-13 → keep old behaviour
                if (hasReadPermission()) {
                    this.launchLegacyActivity();
                } else {
                    requestReadPermission();
                }
            }
            return true;
        }
        return false;
    }

    private boolean isSystemPickerCapable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU; // 33
    }

    /**
     * Original behaviour: launch MultiImageChooserActivity
     */
    protected void launchLegacyActivity() throws JSONException {
        final JSONObject params = this.args.getJSONObject(0);
        final Intent imagePickerIntent = new Intent(cordova.getActivity(), MultiImageChooserActivity.class);
        int max = 20;
        int desiredWidth = 0;
        int desiredHeight = 0;
        int quality = 100;
        int outputType = 0;
        if (params.has("maximumImagesCount")) {
            max = params.getInt("maximumImagesCount");
        }
        if (params.has("width")) {
            desiredWidth = params.getInt("width");
        }
        if (params.has("height")) {
            desiredHeight = params.getInt("height");
        }
        if (params.has("quality")) {
            quality = params.getInt("quality");
        }
        if (params.has("outputType")) {
            outputType = params.getInt("outputType");
        }

        imagePickerIntent.putExtra("MAX_IMAGES", max);
        imagePickerIntent.putExtra("WIDTH", desiredWidth);
        imagePickerIntent.putExtra("HEIGHT", desiredHeight);
        imagePickerIntent.putExtra("QUALITY", quality);
        imagePickerIntent.putExtra("OUTPUT_TYPE", outputType);

        cordova.startActivityForResult(this, imagePickerIntent, REQUEST_LEGACY_PICKER);
    }

    /**
     * New: Android 13+ system photo picker
     */
    private void launchSystemPhotoPicker() throws JSONException {
        int max = 20;
        if (this.args != null && this.args.length() > 0 && !this.args.isNull(0)) {
            JSONObject params = this.args.getJSONObject(0);
            max = params.optInt("maximumImagesCount", 20);
        }

        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("image/*");
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        cordova.startActivityForResult(this, intent, REQUEST_SYSTEM_PICKER);
    }

    @SuppressLint("InlinedApi")
    private boolean hasReadPermission() {
        return Build.VERSION.SDK_INT < 23 ||
                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) ||
                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.READ_MEDIA_IMAGES);
    }

    @SuppressLint("InlinedApi")
    private void requestReadPermission() {
        if (!hasReadPermission()) {
            if (Build.VERSION.SDK_INT < 33) {
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            } else {
                // You can remove this branch entirely if you want to fully stop requesting on 33+
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.READ_MEDIA_IMAGES});
            }
            return;
        }
        callbackContext.success(1);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 1) New Android 13+ system picker path
        if (requestCode == REQUEST_SYSTEM_PICKER) {
            handleSystemPickerResult(resultCode, data);
            return;
        }

        // 2) Legacy path (your original code)
        if (requestCode == REQUEST_LEGACY_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                int sync = data.getIntExtra("bigdata:synccode", -1);
                final Bundle bigData = ResultIPC.get().getLargeData(sync);

                ArrayList<String> fileNames = bigData.getStringArrayList("MULTIPLEFILENAMES");

                JSONArray res = new JSONArray(fileNames);
                callbackContext.success(res);

            } else if (resultCode == Activity.RESULT_CANCELED && data != null) {
                String error = data.getStringExtra("ERRORMESSAGE");
                callbackContext.error(error);

            } else if (resultCode == Activity.RESULT_CANCELED) {
                JSONArray res = new JSONArray();
                callbackContext.success(res);

            } else {
                callbackContext.error("No images selected");
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Handle Android 13+ picker result: copy to cache → return file://...
     */
    private void handleSystemPickerResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            callbackContext.success(new JSONArray());
            return;
        }

        try {
            ArrayList<Uri> pickedUris = new ArrayList<>();

            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    pickedUris.add(uri);
                }
            } else if (data.getData() != null) {
                pickedUris.add(data.getData());
            }

            JSONArray result = new JSONArray();
            for (Uri uri : pickedUris) {
                String localPath = copyUriToCache(uri);
                result.put("file://" + localPath);
            }

            callbackContext.success(result);
        } catch (Exception e) {
            callbackContext.error("Failed to get images: " + e.getMessage());
        }
    }

    /**
     * Copy a content:// URI to our app cache and return absolute path
     */
    private String copyUriToCache(Uri uri) throws IOException {
        Activity activity = cordova.getActivity();
        InputStream in = activity.getContentResolver().openInputStream(uri);

        File outDir = activity.getCacheDir();
        String fileName = "picked_" + System.currentTimeMillis() + ".jpg";
        File outFile = new File(outDir, fileName);

        OutputStream out = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        out.flush();
        out.close();

        return outFile.getAbsolutePath();
    }

    /**
     * Choosing a picture launches another Activity, so we need to implement the
     * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
     * before we get the launched Activity's result.
     *
     * @see http://cordova.apache.org/docs/en/dev/guide/platforms/android/plugin.html#launching-other-activities
     */
    @Override
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
    }

    @Override
    public void onRequestPermissionResult(int requestCode,
                                          String[] permissions,
                                          int[] grantResults) throws JSONException {

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.launchLegacyActivity();
            } else {
                callbackContext.error("Permission denied");
            }
        }
    }

}
