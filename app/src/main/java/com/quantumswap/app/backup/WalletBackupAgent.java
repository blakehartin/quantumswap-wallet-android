package com.quantumswap.app.backup;

import android.app.backup.BackupAgentHelper;
import android.app.backup.FullBackupDataOutput;

import com.quantumswap.app.utils.PrefConnect;

import java.io.IOException;

public class WalletBackupAgent extends BackupAgentHelper {

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        boolean enabled = PrefConnect.readBoolean(this, PrefConnect.BACKUP_ENABLED_KEY, false);
        if (enabled) {
            super.onFullBackup(data);
        }
    }

    @Override
    public void onRestoreFinished() {
        super.onRestoreFinished();
    }
}
