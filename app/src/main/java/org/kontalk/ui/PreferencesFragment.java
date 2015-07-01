/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.io.File;

import com.github.machinarius.preferencefragment.PreferenceFragment;

import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.ServerList;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.service.ServerListUpdater;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.service.msgcenter.PushServiceManager;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;

import static org.kontalk.crypto.PersonalKeyImporter.KEYPACK_FILENAME;


/**
 * PreferencesFragment.
 * @author Daniele Ricci
 * @author Andrea Cappelli
 */
public final class PreferencesFragment extends PreferenceFragment {
    private static final String TAG = Kontalk.TAG;

    private static final int REQUEST_PICK_BACKGROUND = Activity.RESULT_FIRST_USER + 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // upgrade from old version: pref_text_enter becomes string
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        try {
            prefs.getString("pref_text_enter", null);
        }
        catch (ClassCastException e) {
            // legacy mode
            prefs.edit()
                .putString("pref_text_enter",
                    prefs.getBoolean("pref_text_enter", false) ?
                        "newline" : "default")
                .commit();
        }

        addPreferencesFromResource(R.xml.preferences);

        // push notifications checkbox
        final Preference pushNotifications = findPreference("pref_push_notifications");
        pushNotifications.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Context ctx = getActivity();
                CheckBoxPreference pref = (CheckBoxPreference) preference;
                if (pref.isChecked())
                    MessageCenterService.enablePushNotifications(ctx.getApplicationContext());
                else
                    MessageCenterService.disablePushNotifications(ctx.getApplicationContext());

                return true;
            }
        });

        // message center restart
        final Preference restartMsgCenter = findPreference("pref_restart_msgcenter");
        restartMsgCenter.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Log.w(TAG, "manual message center restart requested");
                Context ctx = getActivity();
                MessageCenterService.restart(ctx.getApplicationContext());
                Toast.makeText(ctx, R.string.msg_msgcenter_restarted, Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        // change passphrase
        final Preference changePassphrase = findPreference("pref_change_passphrase");
        changePassphrase.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {

                if (Authenticator.isUserPassphrase(getActivity())) {

                    OnPassphraseRequestListener action = new OnPassphraseRequestListener() {
                        public void onValidPassphrase(String passphrase) {
                            askNewPassphrase();
                        }

                        public void onInvalidPassphrase() {
                            new AlertDialog.Builder(getActivity())
                                .setTitle(R.string.title_passphrase)
                                .setMessage(R.string.err_password_invalid)
                                .show();
                        }
                    };
                    askCurrentPassphrase(action);
                }

                else {
                    askNewPassphrase();
                }


                return true;
            }
        });

        // regenerate key pair
        final Preference regenKeyPair = findPreference("pref_regenerate_keypair");
        regenKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.pref_regenerate_keypair)
                    .setMessage(R.string.pref_regenerate_keypair_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Context ctx = getActivity();
                            Toast.makeText(ctx, R.string.msg_generating_keypair,
                                Toast.LENGTH_LONG).show();

                            MessageCenterService.regenerateKeyPair(ctx.getApplicationContext());
                        }
                    })
                    .show();

                return true;
            }
        });

        // export key pair
        final Preference exportKeyPair = findPreference("pref_export_keypair");
        exportKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {

                // TODO check for external storage presence

                final OnPassphraseChangedListener action = new OnPassphraseChangedListener() {
                    public void onPassphraseChanged(String passphrase) {
                        Context ctx = getActivity();
                        try {
                            Kontalk.get(ctx).exportPersonalKey(passphrase);

                            Toast.makeText(ctx,
                                R.string.msg_keypair_exported,
                                Toast.LENGTH_LONG).show();

                        }
                        catch (Exception e) {

                            Log.e(TAG, "error exporting keys", e);
                            Toast.makeText(ctx,
                                // TODO i18n
                                "Unable to export personal key.",
                                Toast.LENGTH_LONG).show();

                        }
                    }
                };

                // passphrase was never set by the user
                // encrypt it with a user-defined passphrase first
                if (!Authenticator.isUserPassphrase(getActivity())) {
                    askNewPassphrase(action);
                }

                else {
                    OnPassphraseRequestListener action2 = new OnPassphraseRequestListener() {
                        public void onValidPassphrase(String passphrase) {
                            action.onPassphraseChanged(passphrase);
                        }

                        public void onInvalidPassphrase() {
                            new AlertDialog.Builder(getActivity())
                                .setTitle(R.string.pref_export_keypair)
                                .setMessage(R.string.err_password_invalid)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        }
                    };

                    askCurrentPassphrase(action2);
                }

                return true;
            }
        });

        // import key pair
        final Preference importKeyPair = findPreference("pref_import_keypair");
        importKeyPair.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.pref_import_keypair)
                    .setMessage(getString(R.string.msg_import_keypair, KEYPACK_FILENAME))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Uri keypack = Uri.fromFile(new File(Environment
                                .getExternalStorageDirectory(), KEYPACK_FILENAME));
                            Context ctx = getActivity();
                            MessageCenterService.importKeyPair(ctx.getApplicationContext(),
                                keypack, Kontalk.get(ctx).getCachedPassphrase());
                        }
                    })
                    .show();

                return true;
            }
        });

        // delete account
        final Preference deleteAccount = findPreference("pref_delete_account");
        deleteAccount.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.pref_delete_account)
                    .setMessage(R.string.msg_delete_account)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // progress dialog
                            final LockedProgressDialog progress = new LockedProgressDialog(getActivity());
                            progress.setMessage(getString(R.string.msg_delete_account_progress));
                            progress.setIndeterminate(true);
                            progress.show();

                            // stop the message center first
                            Context ctx = getActivity();
                            MessageCenterService.stop(ctx.getApplicationContext());

                            AccountManagerCallback<Boolean> callback = new AccountManagerCallback<Boolean>() {
                                public void run(AccountManagerFuture<Boolean> future) {
                                    // dismiss progress
                                    progress.dismiss();
                                    // exit now
                                    getActivity().finish();
                                }
                            };
                            Authenticator.removeDefaultAccount(ctx, callback);
                        }
                    })
                    .show();

                return true;
            }
        });

        // use custom background
        final Preference customBg = findPreference("pref_custom_background");
        customBg.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                // discard reference to custom background drawable
                Preferences.setCachedCustomBackground(null);
                return false;
            }
        });

        // set background
        final Preference setBackground = findPreference("pref_background_uri");
        setBackground.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQUEST_PICK_BACKGROUND);
                return true;
            }
        });

        // set balloon theme
        final Preference balloons = findPreference("pref_balloons");
        balloons.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Preferences.setCachedBalloonTheme((String) newValue);
                return true;
            }
        });

        // manual server address is handled in Application context
        // we just handle validation here

        // server list last update timestamp
        final Preference updateServerList = findPreference("pref_update_server_list");
        updateServerList.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Context ctx = getActivity();
                final ServerListUpdater updater = new ServerListUpdater(ctx);

                final ProgressDialog diag = new ProgressDialog(ctx);
                diag.setCancelable(true);
                diag.setMessage(getString(R.string.serverlist_updating));
                diag.setIndeterminate(true);
                diag.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        updater.cancel();
                    }
                });

                updater.setListener(new ServerListUpdater.UpdaterListener() {
                    @Override
                    public void error(Throwable e) {
                        diag.cancel();
                        message(R.string.serverlist_update_error);
                    }

                    @Override
                    public void networkNotAvailable() {
                        diag.cancel();
                        message(R.string.serverlist_update_nonetwork);
                    }

                    @Override
                    public void offlineModeEnabled() {
                        diag.cancel();
                        message(R.string.serverlist_update_offline);
                    }

                    @Override
                    public void noData() {
                        diag.cancel();
                        message(R.string.serverlist_update_nodata);
                    }

                    @Override
                    public void updated(final ServerList list) {
                        diag.dismiss();
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Preferences.updateServerListLastUpdate(updateServerList, list);
                                // restart message center
                                MessageCenterService.restart(getActivity().getApplicationContext());
                            }
                        });
                    }

                    private void message(final int textId) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getActivity(), textId,
                                    Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });

                diag.show();
                updater.start();
                return true;
            }
        });

        // update 'last update' string
        ServerList list = ServerListUpdater.getCurrentList(getActivity());
        if (list != null)
            Preferences.updateServerListLastUpdate(updateServerList, list);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setupPreferences(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Activity ctx = getActivity();
                ctx.finish();
                startActivity(new Intent(ctx, ConversationList.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }


    private interface OnPassphraseChangedListener {
        void onPassphraseChanged(String passphrase);
    }

    private interface OnPassphraseRequestListener {
        void onValidPassphrase(String passphrase);

        void onInvalidPassphrase();
    }

    private void askCurrentPassphrase(final OnPassphraseRequestListener action) {
        new InputDialog.Builder(getActivity(),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .setTitle(R.string.title_passphrase)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {

                    String passphrase = InputDialog.getInputText((Dialog) dialog).toString();
                    // user-entered passphrase is hashed, so compare with SHA-1 version
                    String hashed = MessageUtils.sha1(passphrase);
                    if (hashed.equals(Kontalk.get(getActivity())
                        .getCachedPassphrase())) {
                        action.onValidPassphrase(passphrase);
                    }
                    else {
                        action.onInvalidPassphrase();
                    }

                }
            })
            .show();
    }

    private void askNewPassphrase() {
        askNewPassphrase(null);
    }

    private void askNewPassphrase(final OnPassphraseChangedListener action) {
        new PasswordInputDialog.Builder(getActivity())
            .setMinLength(PersonalKey.MIN_PASSPHRASE_LENGTH)
            .setTitle(R.string.pref_change_passphrase)
            .setPositiveButton(android.R.string.ok, new PasswordInputDialog.OnPasswordInputListener() {
                public void onClick(DialogInterface dialog, int which, String password) {
                    Context ctx = getActivity();
                    String oldPassword = Kontalk.get(getActivity()).getCachedPassphrase();
                    try {

                        // user-entered passphrase must be hashed
                        String hashed = MessageUtils.sha1(password);
                        Authenticator.changePassphrase(ctx, oldPassword, hashed, true);
                        Kontalk.get(ctx).invalidatePersonalKey();

                        if (action != null)
                            action.onPassphraseChanged(password);
                    }
                    catch (Exception e) {
                        Toast.makeText(ctx,
                            R.string.err_change_passphrase, Toast.LENGTH_LONG)
                            .show();
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    static void setupPreferences(final PreferenceFragment fragment) {
        // disable push notifications if GCM is not available on the device
        if (!PushServiceManager.getInstance(fragment.getActivity()).isServiceAvailable()) {
            final CheckBoxPreference push = (CheckBoxPreference) fragment
                .findPreference("pref_push_notifications");
            push.setEnabled(false);
            push.setChecked(false);
            push.setSummary(R.string.pref_title_disabled_push_notifications);
        }

        final Preference manualServer = fragment.findPreference("pref_network_uri");
        manualServer.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String value = newValue.toString().trim();
                if (value.length() > 0 && !EndpointServer.validate(value)) {
                    new AlertDialog.Builder(fragment.getActivity())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.pref_network_uri)
                        .setMessage(R.string.err_server_invalid_format)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return false;
                }
                return true;
            }
        });

    }

}
