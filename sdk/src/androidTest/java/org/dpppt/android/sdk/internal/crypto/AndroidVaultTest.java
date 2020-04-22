package org.dpppt.android.sdk.internal.crypto;

import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import com.bottlerocketstudios.vault.SharedPreferenceVault;
import com.bottlerocketstudios.vault.SharedPreferenceVaultFactory;
import com.bottlerocketstudios.vault.SharedPreferenceVaultRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.GeneralSecurityException;

import static org.junit.Assert.assertEquals;

/**
 * This test demonstrates how to use the Android-Vault library,
 * and verifies that data is correctly persisted.
 */
public class AndroidVaultTest {

    @Test
    public void testPersistence() {
        SharedPreferences sharedPreferences1 = getTestSharedPreferences();
        SharedPreferences sharedPreferences2 = getTestSharedPreferences();

        sharedPreferences1.edit()
                .putString("test key", "test value")
                .apply();

        String result = sharedPreferences2
                .getString("test key", "not the expected value");

        assertEquals("test value", result);

    }


    private static SharedPreferenceVault getTestSharedPreferences() {
        try {
            // Create an automatically keyed vault
            SharedPreferenceVault secureVault = SharedPreferenceVaultFactory.getAppKeyedCompatAes256Vault(
                    ApplicationProvider.getApplicationContext(),
                    "dp3t-test",     //Preference file name to store content
                    "dp3t-test-keys",      //Preference file to store key material
                    "dp3t-test",          //App-wide unique key alias
                    37388378,           //App-wide unique vault id
                    "never used \"secret\""    //Random string for pre v18
            );
            return secureVault;
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            return null;
        }
    }
}