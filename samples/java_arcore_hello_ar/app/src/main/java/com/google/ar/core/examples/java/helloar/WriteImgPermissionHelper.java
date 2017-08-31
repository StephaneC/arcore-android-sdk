/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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
 */
package com.google.ar.core.examples.java.helloar;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

/**
 * Helper to ask camera permission.
 */
public class WriteImgPermissionHelper {
  private static final String WRITE_EXTERNAL_STORAGE_PERMISSION = WRITE_EXTERNAL_STORAGE;
  private static final int WRITE_EXTERNAL_STORAGE_PERMISSION_CODE = 10;

  /**
   * Check to see we have the necessary permissions for this app.
   */
  public static boolean hasWritePermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity, WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Check to see we have the necessary permissions for this app, and ask for them if we don't.
   */
  public static void requestWriteStoragePermission(Activity activity) {
    ActivityCompat.requestPermissions(activity, new String[]{WRITE_EXTERNAL_STORAGE},
                                      WRITE_EXTERNAL_STORAGE_PERMISSION_CODE);
  }
}
