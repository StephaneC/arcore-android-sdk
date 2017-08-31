package com.google.ar.core.examples.java.helloar;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

/**
 * Created by SCA on 31/08/2017.
 */

public class ImagesDialog extends DialogFragment{

  public static ImagesDialog newInstance() {
    ImagesDialog frag = new ImagesDialog();
    Bundle args = new Bundle();
    frag.setArguments(args);
    return frag;
  }



  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setTitle(R.string.pick_object)
           .setItems(R.array.objects_array, new DialogInterface.OnClickListener() {
             public void onClick(DialogInterface dialog, int which) {
               // The 'which' argument contains the index position
               // of the selected item
               ((HelloArActivity)getActivity()).selectedObject(which);
             }
           });
    return builder.create();
  }
}
