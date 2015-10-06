package com.door43.translationstudio.newui;

import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.content.FileProvider;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;

import com.door43.tools.reporting.Logger;
import com.door43.translationstudio.AppContext;
import com.door43.translationstudio.R;
import com.door43.translationstudio.core.TargetTranslation;
import com.door43.translationstudio.core.Translator;
import com.door43.translationstudio.filebrowser.FileBrowserActivity;
import com.door43.widget.ViewUtil;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.security.InvalidParameterException;

/**
 * Created by joel on 10/5/2015.
 */
public class BackupDialog extends DialogFragment {

    public static final String ARG_TARGET_TRANSLATION_ID = "target_translation_id";
    private static final int IMPORT_PROJECT_FROM_SD_REQUEST = 0;
    private TargetTranslation mTargetTranslation;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getDialog().requestWindowFeature(Window.FEATURE_NO_TITLE);
        View v = inflater.inflate(R.layout.dialog_backup, container, false);

        // get target translation to backup
        Bundle args = getArguments();
        if(args != null && args.containsKey(ARG_TARGET_TRANSLATION_ID)) {
            String targetTranslationId = args.getString(ARG_TARGET_TRANSLATION_ID, null);
            mTargetTranslation = AppContext.getTranslator().getTargetTranslation(targetTranslationId);
            if(mTargetTranslation == null) {
                throw new InvalidParameterException("The target translation '" + targetTranslationId + "' is invalid");
            }
        } else {
            throw new InvalidParameterException("The target translation id was not specified");
        }

        Button uploadButton = (Button)v.findViewById(R.id.upload_to_cloud);
        Button exportToSDButton = (Button)v.findViewById(R.id.export_to_sd);
        Button importFromSDButton = (Button)v.findViewById(R.id.import_from_sd);
        Button exportToAppButton = (Button)v.findViewById(R.id.export_to_app);

        final String filename = mTargetTranslation.getId() + "." + Translator.ARCHIVE_EXTENSION;

        // backup buttons
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        exportToSDButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File exportFile = new File(AppContext.getPublicDownloadsDirectory(), System.currentTimeMillis() / 1000L + "_" + filename);
                try {
                    AppContext.getTranslator().export(mTargetTranslation, exportFile);
                } catch (Exception e) {
                    Logger.e(BackupDialog.class.getName(), "Failed to export the target translation " + mTargetTranslation.getId(), e);
                }
                if(exportFile.exists()) {
                    Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.success, Snackbar.LENGTH_LONG);
                    ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                    snack.show();
                } else {
                    Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.translation_export_failed, Snackbar.LENGTH_LONG);
                    ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                    snack.show();
                }
            }
        });
        importFromSDButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File path = AppContext.getPublicDownloadsDirectory();
                Intent intent = new Intent(getActivity(), FileBrowserActivity.class);
                intent.setDataAndType(Uri.fromFile(path), "file/*");
                startActivityForResult(intent, IMPORT_PROJECT_FROM_SD_REQUEST);
            }
        });
        exportToAppButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File exportFile = new File(AppContext.getSharingDir(), filename);
                try {
                    AppContext.getTranslator().export(mTargetTranslation, exportFile);
                } catch (Exception e) {
                    Logger.e(BackupDialog.class.getName(), "Failed to export the target translation " + mTargetTranslation.getId(), e);
                }
                if(exportFile.exists()) {
                    Uri u = FileProvider.getUriForFile(getActivity(), "com.door43.translationstudio.fileprovider", exportFile);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    startActivity(Intent.createChooser(i, "Email:"));
                } else {
                    Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.translation_export_failed, Snackbar.LENGTH_LONG);
                    ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                    snack.show();
                }
            }
        });


        // dismiss button
        Button dismissButton = (Button)v.findViewById(R.id.dismiss_button);
        dismissButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        return v;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == IMPORT_PROJECT_FROM_SD_REQUEST) {
            if(data != null) {
                File file = new File(data.getData().getPath());
                if(FilenameUtils.getExtension(file.getName()).toLowerCase().equals(Translator.ARCHIVE_EXTENSION)) {
                    try {
                        AppContext.getTranslator().importArchive(file);
                        Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.success, Snackbar.LENGTH_LONG);
                        ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                        snack.show();
                    } catch (Exception e) {
                        Logger.e(this.getClass().getName(), "Failed to import the archive", e);
                        Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.translation_import_failed, Snackbar.LENGTH_LONG);
                        ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                        snack.show();
                    }
                } else {
                    Snackbar snack = Snackbar.make(getActivity().findViewById(android.R.id.content), R.string.invalid_file, Snackbar.LENGTH_LONG);
                    ViewUtil.setSnackBarTextColor(snack, getResources().getColor(R.color.light_primary_text));
                    snack.show();
                }
            }
        }
    }
}
