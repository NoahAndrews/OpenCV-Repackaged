/*
 * Copyright (c) 2019 FTC team 4634 FROGbots
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.openftc.opencvrepackaged;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;

import com.qualcomm.robotcore.eventloop.opmode.AnnotatedOpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegistrar;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

public class DynamicOpenCvNativeLibLoader
{
    private static final String NATIVE_LIB_MD5 = "83995833bd64b46a940e5eda8dafd620";
    private static boolean alreadyLoaded = false;

    private File libInProtectedStorage;
    private File protectedExtraFolder;
    private File libOnSdcard;
    private Activity rcActivity;
    private CountDownLatch nativeLibLoadedLatch = new CountDownLatch(1);

    /*
     * By annotating this method with @OpModeRegistrar, it will be called
     * automatically by the SDK as it is scanning all the classes in the app
     * (for @Teleop, etc.) while it is "starting" the robot.
     */
    @OpModeRegistrar
    public static void loadNativeLibOnStartRobot(Context context, AnnotatedOpModeManager manager)
    {
        /*
         * Because this is called every time the robot is "restarted" we
         * check to see whether we've already previously done our job here.
         */
        if(alreadyLoaded)
        {
            /*
             * Get out of dodge
             */
            return;
        }

        DynamicOpenCvNativeLibLoader loader = new DynamicOpenCvNativeLibLoader();
        loader.setupOpenCVNativeLib();

        try
        {
            /*
             * If the native library was successfully loaded,
             * then this latch will have already been released
             * and this statement will return instantly. However,
             * If the attempt to load the library failed, then
             * this latch will (intentionally) never release and
             * so we will hang the RC app.
             */
            loader.nativeLibLoadedLatch.await();
            alreadyLoaded = true;
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void setupOpenCVNativeLib()
    {
        rcActivity = AppUtil.getInstance().getRootActivity();

        try
        {
            /*
             * Attempt to set up the OpenCV library for loading in
             * the next statement
             */
            setupOpenCvFiles();

            /*
             * We've been given the go-ahead! Load up libVuforiaReal.so
             */
            System.load(libInProtectedStorage.getAbsolutePath());

            nativeLibLoadedLatch.countDown();
        }
        catch (OpenCvNativeLibNotFoundException e)
        {
            e.printStackTrace();
            showLibNotOnSdcardDialog();
        }
        catch (OpenCvNativeLibCorruptedException e)
        {
            e.printStackTrace();
            showLibCorruptedDialog();
        }
    }

    private void setupOpenCvFiles() throws OpenCvNativeLibNotFoundException, OpenCvNativeLibCorruptedException
    {
        libInProtectedStorage = new File(rcActivity.getFilesDir() + "/extra/libOpenCvNative.so");
        protectedExtraFolder = new File(rcActivity.getFilesDir() + "/extra/");
        libOnSdcard = new File(Environment.getExternalStorageDirectory() + "/FIRST/libOpenCvNative.so");

        /*
         * First, check to see if it exists in the protected storage
         */
        if(!libInProtectedStorage.exists())
        {
            /*
             * Ok, so it's not in the protected storage. Check if it exists
             * in the FIRST folder on the SDcard
             */
            if(libOnSdcard.exists())
            {
                /*
                 * Yup, it exists, but we need to verify the integrity of the file
                 * with the MD5 hash before we copy it, otherwise bad things might
                 * happen when we try to load a corrupted lib...
                 */
                if(MD5.checkMD5(NATIVE_LIB_MD5, libOnSdcard))
                {
                    /*
                     * Alright, everything checks out, so copy it to the protected
                     * storage and continue with the app launch!
                     */
                    copyLibFromSdcardToProtectedStorage();
                }
                else
                {
                    /*
                     * Oooh, not good - it's corrupted.
                     * Show the user a dialog explaining the situation
                     */
                    throw new OpenCvNativeLibCorruptedException();
                }
            }
            else
            {
                /*
                 * Welp, it doesn't exist on the SDcard either :(
                 * Show the user a dialog explaining the situation
                 */
                throw new OpenCvNativeLibNotFoundException();
            }
        }
        else
        {
            /*
             * Ok so it does exist in the protected storage. This means that we ourselves
             * copied it in here earlier. Ordinarily this would mean that we do not need
             * to do an MD5 check on it, since it would have been checked before it was
             * copied in. However, in the case that the user upgrades the OpenCV Java SDK
             * version and a previous version of the native lib is already in the protected
             * storage, then things would likely explode. So, we check the MD5 here anyway.
             * It doesn't really cost *that* much CPU time anyway; <200ms
             */
            if(!MD5.checkMD5(NATIVE_LIB_MD5, libInProtectedStorage))
            {
                throw new OpenCvNativeLibCorruptedException();
            }
        }
    }

    private void showLibNotOnSdcardDialog()
    {
        rcActivity.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                String msg = "libOpenCvNative.so was not found. Please copy it to the FIRST folder on the internal storage.";

                AlertDialog dialog = new AlertDialog.Builder(rcActivity)
                        .setTitle("libOpenCvNative.so not found!")
                        .setMessage(msg)
                        .setCancelable(false)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i)
                            {
                                System.exit(1);
                            }
                        }).create();
                dialog.show();
            }
        });
    }

    private void showLibCorruptedDialog()
    {
        rcActivity.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                AlertDialog.Builder builder = new AlertDialog.Builder(rcActivity);
                builder.setTitle("libOpenCvNative.so corrupted!");
                builder.setMessage("libOpenCvNative.so is present in the FIRST on the internal storage. However, the MD5 " +
                        "checksum does not match what is expected. Delete and re-download the file.");
                builder.setCancelable(false);
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i)
                    {
                        System.exit(1);
                    }
                });
                builder.show();
            }
        });
    }

    private void copyLibFromSdcardToProtectedStorage()
    {
        /*
         * Check if the 'extra' folder exists. If it doesn't,
         * then create it now or else the copy code will crash
         */
        if(!protectedExtraFolder.exists())
        {
            protectedExtraFolder.mkdir();
        }

        try
        {
            /*
             * Copy the file with a 1MiB buffer
             */
            InputStream is = new FileInputStream(libOnSdcard);
            OutputStream os = new FileOutputStream(libInProtectedStorage);
            byte[] buff = new byte[1024];
            int len;
            while ((len = is.read(buff)) > 0)
            {
                os.write(buff, 0, len);
            }
            is.close();
            os.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}