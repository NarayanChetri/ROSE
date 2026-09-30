package dev.narayan.rose.shizuku;

import android.os.ParcelFileDescriptor;

interface IShizukuService {
    void destroy() = 16777114;
    ParcelFileDescriptor openFile(String path, int mode) = 1;
    boolean exists(String path) = 2;
    long getFileSize(String path) = 3;
}
