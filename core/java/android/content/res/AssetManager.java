/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.annotation.AnyRes;
import android.annotation.ArrayRes;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.ComposedIconInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ThemeUtils;
import android.content.res.Configuration.NativeConfig;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Provides access to an application's raw asset files; see {@link Resources}
 * for the way most applications will want to retrieve their resource data.
 * This class presents a lower-level API that allows you to open and read raw
 * files that have been bundled with the application as a simple stream of
 * bytes.
 */
public final class AssetManager implements AutoCloseable {
    /* modes used when opening an asset */

    /**
     * Mode for {@link #open(String, int)}: no specific information about how
     * data will be accessed.
     */
    public static final int ACCESS_UNKNOWN = 0;
    /**
     * Mode for {@link #open(String, int)}: Read chunks, and seek forward and
     * backward.
     */
    public static final int ACCESS_RANDOM = 1;
    /**
     * Mode for {@link #open(String, int)}: Read sequentially, with an
     * occasional forward seek.
     */
    public static final int ACCESS_STREAMING = 2;
    /**
     * Mode for {@link #open(String, int)}: Attempt to load contents into
     * memory, for fast small reads.
     */
    public static final int ACCESS_BUFFER = 3;

    private static final String TAG = "AssetManager";
    private static final boolean localLOGV = false || false;
    
    private static final boolean DEBUG_REFS = false;
    
    private static final Object sSync = new Object();
    /*package*/ static AssetManager sSystem = null;

    private final TypedValue mValue = new TypedValue();
    private final long[] mOffsets = new long[2];
    
    // For communication with native code.
    private long mObject;

    private StringBlock mStringBlocks[] = null;
    
    private int mNumRefs = 1;
    private boolean mOpen = true;
    private HashMap<Long, RuntimeException> mRefStacks;
 
    private String mAppName;

    private boolean mThemeSupport;
    private String mThemePackageName;
    private String mIconPackageName;
    private String mCommonResPackageName;
    private ArrayList<Integer> mThemeCookies = new ArrayList<Integer>(2);
    private int mIconPackCookie = 0;
    private int mCommonResCookie = 0;
    private SparseArray<PackageItemInfo> mIcons;
    private ComposedIconInfo mComposedIconInfo;

    static IPackageManager sPackageManager;

    /**
     * Number of default assets attached to a Resource object's AssetManager
     * This currently includes framework and cmsdk resources
     */
    private static final int NUM_DEFAULT_ASSETS = 2;


    /**
     * Create a new AssetManager containing only the basic system assets.
     * Applications will not generally use this method, instead retrieving the
     * appropriate asset manager with {@link Resources#getAssets}.    Not for
     * use by applications.
     * {@hide}
     */
    public AssetManager() {
        synchronized (this) {
            if (DEBUG_REFS) {
                mNumRefs = 0;
                incRefsLocked(this.hashCode());
            }
            init(false);
            if (localLOGV) Log.v(TAG, "New asset manager: " + this);
            ensureSystemAssets();
        }
    }

    private static void ensureSystemAssets() {
        synchronized (sSync) {
            if (sSystem == null) {
                AssetManager system = new AssetManager(true);
                system.makeStringBlocks(null);
                sSystem = system;
            }
        }
    }
    
    private AssetManager(boolean isSystem) {
        if (DEBUG_REFS) {
            synchronized (this) {
                mNumRefs = 0;
                incRefsLocked(this.hashCode());
            }
        }
        init(true);
        if (localLOGV) Log.v(TAG, "New asset manager: " + this);
    }

    /**
     * Return a global shared asset manager that provides access to only
     * system assets (no application assets).
     * {@hide}
     */
    public static AssetManager getSystem() {
        ensureSystemAssets();
        return sSystem;
    }

    /**
     * Close this asset manager.
     */
    public void close() {
        synchronized(this) {
            //System.out.println("Release: num=" + mNumRefs
            //                   + ", released=" + mReleased);
            if (mOpen) {
                mOpen = false;
                decRefsLocked(this.hashCode());
            }
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @return the string value, or {@code null}
     */
    @Nullable
    final CharSequence getResourceText(@StringRes int resId) {
        synchronized (this) {
            final TypedValue outValue = mValue;
            if (getResourceValue(resId, 0, outValue, true)) {
                return outValue.coerceToString();
            }
            return null;
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param bagEntryId
     * @return the string value, or {@code null}
     */
    @Nullable
    final CharSequence getResourceBagText(@StringRes int resId, int bagEntryId) {
        synchronized (this) {
            final TypedValue outValue = mValue;
            final int block = loadResourceBagValue(resId, bagEntryId, outValue, true);
            if (block < 0) {
                return null;
            }
            if (outValue.type == TypedValue.TYPE_STRING) {
                return mStringBlocks[block].get(outValue.data);
            }
            return outValue.coerceToString();
        }
    }

    /**
     * Retrieves the string array associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier of the string array
     * @return the string array, or {@code null}
     */
    @Nullable
    final String[] getResourceStringArray(@ArrayRes int resId) {
        return getArrayStringResource(resId);
    }

    /**
     * Populates {@code outValue} with the data associated a particular
     * resource identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param densityDpi the density bucket for which to load the resource
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    final boolean getResourceValue(@AnyRes int resId, int densityDpi, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        final int block = loadResourceValue(resId, (short) densityDpi, outValue, resolveRefs);
        if (block < 0) {
            return false;
        }
        if (outValue.type == TypedValue.TYPE_STRING) {
            outValue.string = mStringBlocks[block].get(outValue.data);
        }
        return true;
    }

    /**
     * Retrieve the text array associated with a particular resource
     * identifier.
     *
     * @param resId the resource id of the string array
     */
    final CharSequence[] getResourceTextArray(@ArrayRes int resId) {
        final int[] rawInfoArray = getArrayStringInfo(resId);
        final int rawInfoArrayLen = rawInfoArray.length;
        final int infoArrayLen = rawInfoArrayLen / 2;
        int block;
        int index;
        final CharSequence[] retArray = new CharSequence[infoArrayLen];
        for (int i = 0, j = 0; i < rawInfoArrayLen; i = i + 2, j++) {
            block = rawInfoArray[i];
            index = rawInfoArray[i + 1];
            retArray[j] = index >= 0 ? mStringBlocks[block].get(index) : null;
        }
        return retArray;
    }

    /**
     * Populates {@code outValue} with the data associated with a particular
     * resource identifier for the current configuration. Resolves theme
     * attributes against the specified theme.
     *
     * @param theme the native pointer of the theme
     * @param resId the resource identifier to load
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    final boolean getThemeValue(long theme, @AnyRes int resId, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        final int block = loadThemeAttributeValue(theme, resId, outValue, resolveRefs);
        if (block < 0) {
            return false;
        }
        if (outValue.type == TypedValue.TYPE_STRING) {
            final StringBlock[] blocks = ensureStringBlocks();
            outValue.string = blocks[block].get(outValue.data);
        }
        return true;
    }

    /**
     * Ensures the string blocks are loaded.
     *
     * @return the string blocks
     */
    @NonNull
    final StringBlock[] ensureStringBlocks() {
        synchronized (this) {
            if (mStringBlocks == null) {
                makeStringBlocks(sSystem.mStringBlocks);
            }
            return mStringBlocks;
        }
    }

    /*package*/ final void recreateStringBlocks() {
        synchronized (this) {
            makeStringBlocks(sSystem.mStringBlocks);
        }
    }

    /*package*/ final void makeStringBlocks(StringBlock[] seed) {
        final int seedNum = (seed != null) ? seed.length : 0;
        final int num = getStringBlockCount();
        mStringBlocks = new StringBlock[num];
        if (localLOGV) Log.v(TAG, "Making string blocks for " + this
                + ": " + num);
        for (int i=0; i<num; i++) {
            if (i < seedNum) {
                mStringBlocks[i] = seed[i];
            } else {
                mStringBlocks[i] = new StringBlock(getNativeStringBlock(i), true);
            }
        }
    }

    /*package*/ final CharSequence getPooledStringForCookie(int cookie, int id) {
        // Cookies map to string blocks starting at 1.
        return mStringBlocks[cookie - 1].get(id);
    }

    /**
     * Open an asset using ACCESS_STREAMING mode.  This provides access to
     * files that have been bundled with an application as assets -- that is,
     * files placed in to the "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be
     *                 hierarchical.
     * 
     * @see #open(String, int)
     * @see #list
     */
    public final InputStream open(String fileName) throws IOException {
        return open(fileName, ACCESS_STREAMING);
    }

    /**
     * Open an asset using an explicit access mode, returning an InputStream to
     * read its contents.  This provides access to files that have been bundled
     * with an application as assets -- that is, files placed in to the
     * "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be
     *                 hierarchical.
     * @param accessMode Desired access mode for retrieving the data.
     * 
     * @see #ACCESS_UNKNOWN
     * @see #ACCESS_STREAMING
     * @see #ACCESS_RANDOM
     * @see #ACCESS_BUFFER
     * @see #open(String)
     * @see #list
     */
    public final InputStream open(String fileName, int accessMode)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long asset = openAsset(fileName, accessMode);
            if (asset != 0) {
                AssetInputStream res = new AssetInputStream(asset);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset file: " + fileName);
    }

    public final AssetFileDescriptor openFd(String fileName)
            throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            ParcelFileDescriptor pfd = openAssetFd(fileName, mOffsets);
            if (pfd != null) {
                return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
            }
        }
        throw new FileNotFoundException("Asset file: " + fileName);
    }

    /**
     * Return a String array of all the assets at the given path.
     * 
     * @param path A relative path within the assets, i.e., "docs/home.html".
     * 
     * @return String[] Array of strings, one for each asset.  These file
     *         names are relative to 'path'.  You can open the file by
     *         concatenating 'path' and a name in the returned string (via
     *         File) and passing that to open().
     * 
     * @see #open
     */
    public native final String[] list(String path)
        throws IOException;

    /**
     * {@hide}
     * Open a non-asset file as an asset using ACCESS_STREAMING mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     * 
     * @see #open(String)
     */
    public final InputStream openNonAsset(String fileName) throws IOException {
        return openNonAsset(0, fileName, ACCESS_STREAMING);
    }

    /**
     * {@hide}
     * Open a non-asset file as an asset using a specific access mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     * 
     * @see #open(String, int)
     */
    public final InputStream openNonAsset(String fileName, int accessMode)
        throws IOException {
        return openNonAsset(0, fileName, accessMode);
    }

    /**
     * {@hide}
     * Open a non-asset in a specified package.  Not for use by applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     */
    public final InputStream openNonAsset(int cookie, String fileName)
        throws IOException {
        return openNonAsset(cookie, fileName, ACCESS_STREAMING);
    }

    /**
     * {@hide}
     * Open a non-asset in a specified package.  Not for use by applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     * @param accessMode Desired access mode for retrieving the data.
     */
    public final InputStream openNonAsset(int cookie, String fileName, int accessMode)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long asset = openNonAssetNative(cookie, fileName, accessMode);
            if (asset != 0) {
                AssetInputStream res = new AssetInputStream(asset);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset absolute file: " + fileName);
    }

    public final AssetFileDescriptor openNonAssetFd(String fileName)
            throws IOException {
        return openNonAssetFd(0, fileName);
    }
    
    public final AssetFileDescriptor openNonAssetFd(int cookie,
            String fileName) throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            ParcelFileDescriptor pfd = openNonAssetFdNative(cookie,
                    fileName, mOffsets);
            if (pfd != null) {
                return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
            }
        }
        throw new FileNotFoundException("Asset absolute file: " + fileName);
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param fileName The name of the file to retrieve.
     */
    public final XmlResourceParser openXmlResourceParser(String fileName)
            throws IOException {
        return openXmlResourceParser(0, fileName);
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName The name of the file to retrieve.
     */
    public final XmlResourceParser openXmlResourceParser(int cookie,
            String fileName) throws IOException {
        XmlBlock block = openXmlBlockAsset(cookie, fileName);
        XmlResourceParser rp = block.newParser();
        block.close();
        return rp;
    }

    /**
     * {@hide}
     * Retrieve a non-asset as a compiled XML file.  Not for use by
     * applications.
     * 
     * @param fileName The name of the file to retrieve.
     */
    /*package*/ final XmlBlock openXmlBlockAsset(String fileName)
            throws IOException {
        return openXmlBlockAsset(0, fileName);
    }

    /**
     * {@hide}
     * Retrieve a non-asset as a compiled XML file.  Not for use by
     * applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     */
    /*package*/ final XmlBlock openXmlBlockAsset(int cookie, String fileName)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long xmlBlock = openXmlAssetNative(cookie, fileName);
            if (xmlBlock != 0) {
                XmlBlock res = new XmlBlock(this, xmlBlock);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset XML file: " + fileName);
    }

    /*package*/ void xmlBlockGone(int id) {
        synchronized (this) {
            decRefsLocked(id);
        }
    }

    /*package*/ final long createTheme() {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long res = newTheme();
            incRefsLocked(res);
            return res;
        }
    }

    /*package*/ final void releaseTheme(long theme) {
        synchronized (this) {
            deleteTheme(theme);
            decRefsLocked(theme);
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (DEBUG_REFS && mNumRefs != 0) {
                Log.w(TAG, "AssetManager " + this
                        + " finalized with non-zero refs: " + mNumRefs);
                if (mRefStacks != null) {
                    for (RuntimeException e : mRefStacks.values()) {
                        Log.w(TAG, "Reference from here", e);
                    }
                }
            }
            destroy();
        } finally {
            super.finalize();
        }
    }
    
    public final class AssetInputStream extends InputStream {
        /**
         * @hide
         */
        public final int getAssetInt() {
            throw new UnsupportedOperationException();
        }
        /**
         * @hide
         */
        public final long getNativeAsset() {
            return mAsset;
        }
        private AssetInputStream(long asset)
        {
            mAsset = asset;
            mLength = getAssetLength(asset);
        }
        public final int read() throws IOException {
            return readAssetChar(mAsset);
        }
        public final boolean markSupported() {
            return true;
        }
        public final int available() throws IOException {
            long len = getAssetRemainingLength(mAsset);
            return len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)len;
        }
        public final void close() throws IOException {
            synchronized (AssetManager.this) {
                if (mAsset != 0) {
                    destroyAsset(mAsset);
                    mAsset = 0;
                    decRefsLocked(hashCode());
                }
            }
        }
        public final void mark(int readlimit) {
            mMarkPos = seekAsset(mAsset, 0, 0);
        }
        public final void reset() throws IOException {
            seekAsset(mAsset, mMarkPos, -1);
        }
        public final int read(byte[] b) throws IOException {
            return readAsset(mAsset, b, 0, b.length);
        }
        public final int read(byte[] b, int off, int len) throws IOException {
            return readAsset(mAsset, b, off, len);
        }
        public final long skip(long n) throws IOException {
            long pos = seekAsset(mAsset, 0, 0);
            if ((pos+n) > mLength) {
                n = mLength-pos;
            }
            if (n > 0) {
                seekAsset(mAsset, n, 0);
            }
            return n;
        }

        protected void finalize() throws Throwable
        {
            close();
        }

        private long mAsset;
        private long mLength;
        private long mMarkPos;
    }

    /**
     * Add an additional set of assets to the asset manager.  This can be
     * either a directory or ZIP file.  Not for use by applications.  Returns
     * the cookie of the added asset, or 0 on failure.
     * {@hide}
     */
    public final int addAssetPath(String path) {
        return  addAssetPathInternal(path, false);
    }

    /**
     * Add an application assets to the asset manager and loading it as shared library.
     * This can be either a directory or ZIP file.  Not for use by applications.  Returns
     * the cookie of the added asset, or 0 on failure.
     * {@hide}
     */
    public final int addAssetPathAsSharedLibrary(String path) {
        return addAssetPathInternal(path, true);
    }

    private final int addAssetPathInternal(String path, boolean appAsLib) {
        synchronized (this) {
            int res = addAssetPathNative(path, appAsLib);
            makeStringBlocks(mStringBlocks);
            return res;
        }
    }

    private native final int addAssetPathNative(String path, boolean appAsLib);

    /**
     * Add a set of assets to overlay an already added set of assets.
     *
     * This is only intended for application resources. System wide resources
     * are handled before any Java code is executed.
     *
     * {@hide}
     */
    public final int addOverlayPath(String idmapPath) {
        return addOverlayPath(idmapPath, null, null, null, null);
    }

     /**
     * Add a set of assets to overlay an already added set of assets.
     *
     * This is only intended for application resources. System wide resources
     * are handled before any Java code is executed.
     *
     * {@hide}
     */
     public final int addOverlayPath(String idmapPath, String themeApkPath,
             String resApkPath, String targetPkgPath, String prefixPath) {
        synchronized (this) {
            int res = addOverlayPathNative(idmapPath, themeApkPath, resApkPath, targetPkgPath,
                    prefixPath);
            makeStringBlocks(mStringBlocks);
            return res;
        }
    }

    /**
     * See addOverlayPath.
     *
     * {@hide}
     */
    public native final int addOverlayPathNative(String idmapPath, String themeApkPath,
            String resApkPath, String targetPkgPath, String prefixPath);
    /**
     * Add a set of common assets.
     *
     * {@hide}
     */
    public final int addCommonOverlayPath(String themeApkPath,
            String resApkPath, String prefixPath) {
        synchronized (this) {
            return addCommonOverlayPathNative(themeApkPath, resApkPath, prefixPath);
        }
    }

    private native final int addCommonOverlayPathNative(String themeApkPath,
            String resApkPath, String prefixPath);

    /**
     * Add a set of assets as an icon pack. A pkgIdOverride value will change the package's id from
     * what is in the resource table to a new value. Manage this carefully, if icon pack has more
     * than one package then that next package's id will use pkgIdOverride+1.
     *
     * Icon packs are different from overlays as they have a different pkg id and
     * do not use idmap so no targetPkg is required
     *
     * {@hide}
     */
    public final int addIconPath(String idmapPath, String resApkPath,
            String prefixPath, int pkgIdOverride) {
        synchronized (this) {
            return addIconPathNative(idmapPath, resApkPath, prefixPath, pkgIdOverride);
        }
    }

    private native final int addIconPathNative(String idmapPath,
            String resApkPath, String prefixPath, int pkgIdOverride);

    /**
    * Delete a set of overlay assets from the asset manager. Not for use by
    * applications. Returns true if succeeded or false on failure.
    *
    * Also works for icon packs
    *
    * {@hide}
    */
    public final boolean removeOverlayPath(String packageName, int cookie) {
        synchronized (this) {
            return removeOverlayPathNative(packageName, cookie);
        }
    }

    private native final boolean removeOverlayPathNative(String packageName, int cookie);

    /**
     * Add multiple sets of assets to the asset manager at once.  See
     * {@link #addAssetPath(String)} for more information.  Returns array of
     * cookies for each added asset with 0 indicating failure, or null if
     * the input array of paths is null.
     * {@hide}
     */
    public final int[] addAssetPaths(String[] paths) {
        if (paths == null) {
            return null;
        }

        int[] cookies = new int[paths.length];
        for (int i = 0; i < paths.length; i++) {
            cookies[i] = addAssetPath(paths[i]);
        }

        return cookies;
    }

    /**
     * Sets a flag indicating that this AssetManager should have themes
     * attached, according to the initial request to create it by the
     * ApplicationContext.
     *
     * {@hide}
     */
    public final void setThemeSupport(boolean themeSupport) {
        mThemeSupport = themeSupport;
    }

    /**
     * Should this AssetManager have themes attached, according to the initial
     * request to create it by the ApplicationContext?
     *
     * {@hide}
     */
    public final boolean hasThemeSupport() {
        return mThemeSupport;
    }

    /**
     * Get package name of current icon pack (may return null).
     * {@hide}
     */
    public String getIconPackageName() {
        return mIconPackageName;
    }

    /**
     * Sets icon package name
     * {@hide}
     */
    public void setIconPackageName(String packageName) {
        mIconPackageName = packageName;
    }

    /**
     * Get package name of current common resources (may return null).
     * {@hide}
     */
    public String getCommonResPackageName() {
        return mCommonResPackageName;
    }

    /**
     * Sets common resources package name
     * {@hide}
     */
    public void setCommonResPackageName(String packageName) {
        mCommonResPackageName = packageName;
    }

    /**
     * Get package name of current theme (may return null).
     * {@hide}
     */
    public String getThemePackageName() {
        return mThemePackageName;
    }

    /**
     * Sets package name and highest level style id for current theme (null, 0 is allowed).
     * {@hide}
     */
    public void setThemePackageName(String packageName) {
        mThemePackageName = packageName;
    }

    /**
     * Get asset cookie for current theme (may return 0).
     * {@hide}
     */
    public ArrayList<Integer> getThemeCookies() {
        return mThemeCookies;
    }

    /** {@hide} */
    public void setIconPackCookie(int cookie) {
        mIconPackCookie = cookie;
    }

    /** {@hide} */
    public int getIconPackCookie() {
        return mIconPackCookie;
    }

    /** {@hide} */
    public void setCommonResCookie(int cookie) {
        mCommonResCookie = cookie;
    }

    /** {@hide} */
    public int getCommonResCookie() {
        return mCommonResCookie;
    }

    /**
     * Sets asset cookie for current theme (0 if not a themed asset manager).
     * {@hide}
     */
    public void addThemeCookie(int cookie) {
        mThemeCookies.add(cookie);
    }

    /** {@hide} */
    public String getAppName() {
        return mAppName;
    }

    /** {@hide} */
    public void setAppName(String pkgName) {
        mAppName = pkgName;
    }

    /** {@hide} */
    public boolean hasThemedAssets() {
        return mThemeCookies.size() > 0 || mIconPackCookie != 0;
    }

    /**
     * Determine whether the state in this asset manager is up-to-date with
     * the files on the filesystem.  If false is returned, you need to
     * instantiate a new AssetManager class to see the new data.
     * {@hide}
     */
    public native final boolean isUpToDate();

    /**
     * Get the locales that this asset manager contains data for.
     *
     * <p>On SDK 21 (Android 5.0: Lollipop) and above, Locale strings are valid
     * <a href="https://tools.ietf.org/html/bcp47">BCP-47</a> language tags and can be
     * parsed using {@link java.util.Locale#forLanguageTag(String)}.
     *
     * <p>On SDK 20 (Android 4.4W: Kitkat for watches) and below, locale strings
     * are of the form {@code ll_CC} where {@code ll} is a two letter language code,
     * and {@code CC} is a two letter country code.
     */
    public native final String[] getLocales();

    /**
     * Same as getLocales(), except that locales that are only provided by the system (i.e. those
     * present in framework-res.apk or its overlays) will not be listed.
     *
     * For example, if the "system" assets support English, French, and German, and the additional
     * assets support Cherokee and French, getLocales() would return
     * [Cherokee, English, French, German], while getNonSystemLocales() would return
     * [Cherokee, French].
     * {@hide}
     */
    public native final String[] getNonSystemLocales();

    /** {@hide} */
    public native final Configuration[] getSizeConfigurations();

    /**
     * Change the configuation used when retrieving resources.  Not for use by
     * applications.
     * {@hide}
     */
    public native final void setConfiguration(int mcc, int mnc, String locale,
            int orientation, int touchscreen, int density, int keyboard,
            int keyboardHidden, int navigation, int screenWidth, int screenHeight,
            int smallestScreenWidthDp, int screenWidthDp, int screenHeightDp,
            int screenLayout, int uiMode, int majorVersion);

    /**
     * Retrieve the resource identifier for the given resource name.
     */
    /*package*/ native final int getResourceIdentifier(String type,
                                                       String name,
                                                       String defPackage);

    /*package*/ native final String getResourceName(int resid);
    /*package*/ native final String getResourcePackageName(int resid);
    /*package*/ native final String getResourceTypeName(int resid);
    /*package*/ native final String getResourceEntryName(int resid);
    
    private native final long openAsset(String fileName, int accessMode);
    private final native ParcelFileDescriptor openAssetFd(String fileName,
            long[] outOffsets) throws IOException;
    private native final long openNonAssetNative(int cookie, String fileName,
            int accessMode);
    private native ParcelFileDescriptor openNonAssetFdNative(int cookie,
            String fileName, long[] outOffsets) throws IOException;
    private native final void destroyAsset(long asset);
    private native final int readAssetChar(long asset);
    private native final int readAsset(long asset, byte[] b, int off, int len);
    private native final long seekAsset(long asset, long offset, int whence);
    private native final long getAssetLength(long asset);
    private native final long getAssetRemainingLength(long asset);

    /** Returns true if the resource was found, filling in mRetStringBlock and
     *  mRetData. */
    private native final int loadResourceValue(int ident, short density, TypedValue outValue,
            boolean resolve);
    /** Returns true if the resource was found, filling in mRetStringBlock and
     *  mRetData. */
    private native final int loadResourceBagValue(int ident, int bagEntryId, TypedValue outValue,
                                               boolean resolve);
    /*package*/ static final int STYLE_NUM_ENTRIES = 6;
    /*package*/ static final int STYLE_TYPE = 0;
    /*package*/ static final int STYLE_DATA = 1;
    /*package*/ static final int STYLE_ASSET_COOKIE = 2;
    /*package*/ static final int STYLE_RESOURCE_ID = 3;

    /* Offset within typed data array for native changingConfigurations. */
    static final int STYLE_CHANGING_CONFIGURATIONS = 4;

    /*package*/ static final int STYLE_DENSITY = 5;
    /*package*/ native static final boolean applyStyle(long theme,
            int defStyleAttr, int defStyleRes, long xmlParser,
            int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native static final boolean resolveAttrs(long theme,
            int defStyleAttr, int defStyleRes, int[] inValues,
            int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native final boolean retrieveAttributes(
            long xmlParser, int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native final int getArraySize(int resource);
    /*package*/ native final int retrieveArray(int resource, int[] outValues);
    private native final int getStringBlockCount();
    private native final long getNativeStringBlock(int block);

    /**
     * {@hide}
     */
    public native final String getCookieName(int cookie);

    /**
     * {@hide}
     */
    public native final SparseArray<String> getAssignedPackageIdentifiers();

    /**
     * {@hide}
     */
    public native static final int getGlobalAssetCount();
    
    /**
     * {@hide}
     */
    public native static final String getAssetAllocations();
    
    /**
     * {@hide}
     */
    public native static final int getGlobalAssetManagerCount();
    
    private native final long newTheme();
    private native final void deleteTheme(long theme);
    /*package*/ native static final void applyThemeStyle(long theme, int styleRes, boolean force);
    /*package*/ native static final void copyTheme(long dest, long source);
    /*package*/ native static final void clearTheme(long theme);
    /*package*/ native static final int loadThemeAttributeValue(long theme, int ident,
                                                                TypedValue outValue,
                                                                boolean resolve);
    /*package*/ native static final void dumpTheme(long theme, int priority, String tag, String prefix);
    /*package*/ native static final @NativeConfig int getThemeChangingConfigurations(long theme);

    private native final long openXmlAssetNative(int cookie, String fileName);

    private native final String[] getArrayStringResource(int arrayRes);
    private native final int[] getArrayStringInfo(int arrayRes);
    /*package*/ native final int[] getArrayIntResource(int arrayRes);
    /*package*/ native final int[] getStyleAttributes(int themeRes);

    private native final void init(boolean isSystem);
    /**
     * {@hide}
     */
    public native final int getBasePackageCount();

    /**
     * {@hide}
     */
    public native final String getBasePackageName(int index);

    /**
     * {@hide}
     */
    public native final String getBaseResourcePackageName(int index);

    /**
     * {@hide}
     */
    public native final int getBasePackageId(int index);

    private native final void destroy();

    private final void incRefsLocked(long id) {
        if (DEBUG_REFS) {
            if (mRefStacks == null) {
                mRefStacks = new HashMap<Long, RuntimeException>();
            }
            RuntimeException ex = new RuntimeException();
            ex.fillInStackTrace();
            mRefStacks.put(id, ex);
        }
        mNumRefs++;
    }
    
    private final void decRefsLocked(long id) {
        if (DEBUG_REFS && mRefStacks != null) {
            mRefStacks.remove(id);
        }
        mNumRefs--;
        //System.out.println("Dec streams: mNumRefs=" + mNumRefs
        //                   + " mReleased=" + mReleased);
        if (mNumRefs == 0) {
            destroy();
        }
    }

    private static IPackageManager getPackageManager() {
        if (sPackageManager != null) {
            return sPackageManager;
        }
        IBinder b = ServiceManager.getService("package");
        sPackageManager = IPackageManager.Stub.asInterface(b);
        return sPackageManager;
    }

    /**
     * Attach the necessary theme asset paths and meta information to convert an
     * AssetManager to being globally "theme-aware".
     *
     * @param theme
     * @return true if the AssetManager is now theme-aware; false otherwise.
     *         This can fail, for example, if the theme package has been been
     *         removed and the theme manager has yet to revert formally back to
     *         the framework default.
     *
     * @hide
     */
    public boolean attachThemeAssets(ThemeConfig theme) {
        PackageInfo piTheme = null;
        PackageInfo piTarget = null;
        PackageInfo piAndroid = null;
        PackageInfo piCm = null;

        // Some apps run in process of another app (eg keyguard/systemUI) so we must get the
        // package name from the res tables. The 0th base package name will be the android group.
        // The 1st base package name will be the app group if one is attached. Check if it is there
        // first or else the system will crash!
        String basePackageName;
        String resourcePackageName = null;
        int count = getBasePackageCount();
        if (count > NUM_DEFAULT_ASSETS) {
            basePackageName  = getBasePackageName(NUM_DEFAULT_ASSETS);
            resourcePackageName = getBaseResourcePackageName(NUM_DEFAULT_ASSETS);
        } else if (count == NUM_DEFAULT_ASSETS) {
            basePackageName  = getBasePackageName(0);
        } else {
            return false;
        }

        try {
            piTheme = getPackageManager().getPackageInfo(
                    theme.getOverlayPkgNameForApp(basePackageName), 0,
                    UserHandle.getCallingUserId());
            piTarget = getPackageManager().getPackageInfo(
                    basePackageName, 0, UserHandle.getCallingUserId());

            // Handle special case where a system app (ex trebuchet) may have had its pkg name
            // renamed during an upgrade. basePackageName would be the manifest value which will
            // fail on getPackageInfo(). resource pkg is assumed to have the original name
            if (piTarget == null && resourcePackageName != null) {
                piTarget = getPackageManager().getPackageInfo(resourcePackageName,
                        0, UserHandle.getCallingUserId());
            }
            piAndroid = getPackageManager().getPackageInfo("android", 0,
                    UserHandle.getCallingUserId());
            piCm = getPackageManager().getPackageInfo("cyanogenmod.platform", 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piTheme == null || piTheme.applicationInfo == null ||
                piTarget == null || piTarget.applicationInfo == null ||
                piAndroid == null || piAndroid.applicationInfo == null ||
                piCm == null || piCm.applicationInfo == null ||
                piTheme.overlayTargets == null) {
            return false;
        }

        // Attach themed resources for target
        String themePackageName = piTheme.packageName;
        String themePath = piTheme.applicationInfo.publicSourceDir;
        if (!piTarget.isThemeApk && piTheme.overlayTargets.contains(basePackageName)) {
            String targetPackagePath = piTarget.applicationInfo.sourceDir;
            String prefixPath = ThemeUtils.getOverlayPathToTarget(basePackageName);

            String resCachePath = ThemeUtils.getTargetCacheDir(piTarget.packageName,
                    piTheme.packageName);
            String resApkPath = resCachePath + "/resources.apk";
            String idmapPath = ThemeUtils.getIdmapPath(piTarget.packageName, piTheme.packageName);
            int cookie = addOverlayPath(idmapPath, themePath, resApkPath,
                    targetPackagePath, prefixPath);

            if (cookie != 0) {
                setThemePackageName(themePackageName);
                addThemeCookie(cookie);
            }
        }

        // Attach themed resources for cmsdk
        if (!piTarget.isThemeApk && !piCm.packageName.equals(basePackageName) &&
                piTheme.overlayTargets.contains(piCm.packageName)) {
            String resCachePath= ThemeUtils.getTargetCacheDir(piCm.packageName,
                    piTheme.packageName);
            String prefixPath = ThemeUtils.getOverlayPathToTarget(piCm.packageName);
            String targetPackagePath = piCm.applicationInfo.publicSourceDir;
            String resApkPath = resCachePath + "/resources.apk";
            String idmapPath = ThemeUtils.getIdmapPath(piCm.packageName, piTheme.packageName);
            int cookie = addOverlayPath(idmapPath, themePath,
                    resApkPath, targetPackagePath, prefixPath);
            if (cookie != 0) {
                setThemePackageName(themePackageName);
                addThemeCookie(cookie);
            }
        }

        // Attach themed resources for android framework
        if (!piTarget.isThemeApk && !"android".equals(basePackageName) &&
                piTheme.overlayTargets.contains("android")) {
            String resCachePath= ThemeUtils.getTargetCacheDir(piAndroid.packageName,
                    piTheme.packageName);
            String prefixPath = ThemeUtils.getOverlayPathToTarget(piAndroid.packageName);
            String targetPackagePath = piAndroid.applicationInfo.publicSourceDir;
            String resApkPath = resCachePath + "/resources.apk";
            String idmapPath = ThemeUtils.getIdmapPath("android", piTheme.packageName);
            int cookie = addOverlayPath(idmapPath, themePath,
                    resApkPath, targetPackagePath, prefixPath);
            if (cookie != 0) {
                setThemePackageName(themePackageName);
                addThemeCookie(cookie);
            }
        }

        // attach common assets from theme
        attachCommonAssets(theme);

        return true;
    }

    /**
     * Attach the necessary common asset paths. Common assets should be in a different
     * namespace than the standard 0x7F.
     *
     * @param theme
     * @return true if succes, false otherwise
     *
     * @hide
     */
    public boolean attachCommonAssets(ThemeConfig theme) {
        // Some apps run in process of another app (eg keyguard/systemUI) so we must get the
        // package name from the res tables. The 0th base package name will be the android group.
        // The 1st base package name will be the app group if one is attached. Check if it is there
        // first or else the system will crash!
        String basePackageName;
        int count = getBasePackageCount();
        if (count > NUM_DEFAULT_ASSETS) {
            basePackageName  = getBasePackageName(NUM_DEFAULT_ASSETS);
        } else if (count == NUM_DEFAULT_ASSETS) {
            basePackageName  = getBasePackageName(0);
        } else {
            return false;
        }

        PackageInfo piTheme = null;
        try {
            piTheme = getPackageManager().getPackageInfo(
                    theme.getOverlayPkgNameForApp(basePackageName), 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piTheme == null || piTheme.applicationInfo == null) {
            return false;
        }

        String themePackageName =
                ThemeUtils.getCommonPackageName(piTheme.applicationInfo.packageName);
        if (themePackageName != null && !themePackageName.isEmpty()) {
            String themePath =  piTheme.applicationInfo.publicSourceDir;
            String prefixPath = ThemeUtils.COMMON_RES_PATH;
            String resCachePath =
                    ThemeUtils.getTargetCacheDir(ThemeUtils.COMMON_RES_TARGET, piTheme.packageName);
            String resApkPath = resCachePath + "/resources.apk";
            int cookie = addCommonOverlayPath(themePath, resApkPath,
                    prefixPath);
            if (cookie != 0) {
                setCommonResCookie(cookie);
                setCommonResPackageName(themePackageName);
            }
        }

        return true;
    }

    /**
     * Attach the necessary icon asset paths. Icon assets should be in a different
     * namespace than the standard 0x7F.
     *
     * @param themeConfig
     * @return true if succes, false otherwise
     *
     * @hide
     */
    public boolean attachIconAssets(ThemeConfig themeConfig) {
        PackageInfo piIcon = null;
        try {
            piIcon = getPackageManager().getPackageInfo(themeConfig.getIconPackPkgName(), 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piIcon == null || piIcon.applicationInfo == null) {
            return false;
        }

        String iconPkg = themeConfig.getIconPackPkgName();
        if (iconPkg != null && !iconPkg.isEmpty()) {
            String themeIconPath =  piIcon.applicationInfo.publicSourceDir;
            String prefixPath = ThemeUtils.ICONS_PATH;
            String iconDir = ThemeUtils.getIconPackDir(iconPkg);
            String resApkPath = iconDir + "/resources.apk";

            // Legacy Icon packs have everything in their APK
            if (piIcon.isLegacyIconPackApk) {
                prefixPath = "";
                resApkPath = "";
            }

            int cookie = addIconPath(themeIconPath, resApkPath, prefixPath,
                    Resources.THEME_ICON_PKG_ID);
            setIconPackCookie(cookie);
            if (cookie != 0) {
                setIconPackageName(iconPkg);
            }
        }

        setActivityIcons(themeConfig);

        return true;
    }

    /** @hide */
    public void detachThemeAssets() {
        String themePackageName = getThemePackageName();
        String iconPackageName = getIconPackageName();
        String commonResPackageName = getCommonResPackageName();

        //Remove Icon pack if it exists
        if (!TextUtils.isEmpty(iconPackageName) && getIconPackCookie() > 0) {
            removeOverlayPath(iconPackageName, getIconPackCookie());
            setIconPackageName(null);
            setIconPackCookie(0);
        }
        //Remove common resources if it exists
        if (!TextUtils.isEmpty(commonResPackageName) && getCommonResCookie() > 0) {
            removeOverlayPath(commonResPackageName, getCommonResCookie());
            setCommonResPackageName(null);
            setCommonResCookie(0);
        }
        final List<Integer> themeCookies = getThemeCookies();
        if (!TextUtils.isEmpty(themePackageName) && !themeCookies.isEmpty()) {
            // remove overlays in reverse order
            for (int i = themeCookies.size() - 1; i >= 0; i--) {
                removeOverlayPath(themePackageName, themeCookies.get(i));
            }
        }
        getThemeCookies().clear();
        setThemePackageName(null);
    }

    /** @hide */
    @Nullable
    public PackageItemInfo getPackageItemInfoForResId(int id) {
        return mIcons != null ? mIcons.get(id) : null;
    }

    /**
     * Creates a map between an activity & app's icon ids to its component info. This map
     * is then stored in the resource object.
     * When resource.getDrawable(id) is called it will check this mapping and replace
     * the id with the themed resource id if one is available
     * @param r
     */
    private void setActivityIcons(ThemeConfig themeConfig) {
        SparseArray<PackageItemInfo> iconResources = new SparseArray<>();
        String pkgName = getAppName();
        PackageInfo pkgInfo = null;
        ApplicationInfo appInfo = null;
        final IPackageManager pm = getPackageManager();

        try {
            pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e1) {
            Log.e(TAG, "Unable to get pkg " + pkgName, e1);
            return;
        }

        if (pkgName != null && themeConfig != null &&
                pkgName.equals(themeConfig.getIconPackPkgName())) {
            return;
        }

        //Map application icon
        if (pkgInfo != null && pkgInfo.applicationInfo != null) {
            appInfo = pkgInfo.applicationInfo;
            if (appInfo.themedIcon != 0 || iconResources.get(appInfo.icon) == null) {
                iconResources.put(appInfo.icon, appInfo);
            }
        }

        //Map activity icons.
        if (pkgInfo != null && pkgInfo.activities != null) {
            for (ActivityInfo ai : pkgInfo.activities) {
                if (ai.icon != 0 && (ai.themedIcon != 0 || iconResources.get(ai.icon) == null)) {
                    iconResources.put(ai.icon, ai);
                } else if (appInfo != null && appInfo.icon != 0 &&
                        (ai.themedIcon != 0 || iconResources.get(appInfo.icon) == null)) {
                    iconResources.put(appInfo.icon, ai);
                }
            }
        }

        mIcons = iconResources;
        try {
            ComposedIconInfo iconInfo = pm.getComposedIconInfo();
            mComposedIconInfo = iconInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve ComposedIconInfo", e);
            mComposedIconInfo = null;
        }
    }
}
