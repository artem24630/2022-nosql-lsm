package ru.mail.polis.artyomtrofimov;

import ru.mail.polis.BaseEntry;
import ru.mail.polis.Config;
import ru.mail.polis.Entry;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import static ru.mail.polis.artyomtrofimov.InMemoryDao.DATA_EXT;
import static ru.mail.polis.artyomtrofimov.InMemoryDao.INDEX_EXT;

public final class Utils {
    private static final Random rnd = new Random();
    private static final Lock writeFileListLock = new ReentrantLock();

    private Utils() {
    }

    public static String getUniqueFileName(Deque<String> files) {
        String name;
        do {
            name = generateString();
        } while (files.contains(name));
        return name;
    }

    private static String generateString() {
        char[] chars = new char[rnd.nextInt(8, 9)];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (rnd.nextInt('z' - '0') + '0');
        }
        return new String(chars);
    }

    public static Entry<String> findCeilEntry(RandomAccessFile raf, String key, Path indexPath) throws IOException {
        Entry<String> nextEntry = null;
        try (RandomAccessFile index = new RandomAccessFile(indexPath.toString(), "r")) {
            long lastPos = -1;
            raf.seek(0);
            int size = raf.readInt();
            long left = -1;
            long right = size;
            long mid;
            while (left < right - 1) {
                mid = left + (right - left) / 2;
                index.seek(mid * Long.BYTES);
                long entryPos = index.readLong();
                raf.seek(entryPos);
                raf.readByte(); //read tombstone
                String currentKey = raf.readUTF();
                int keyComparing = currentKey.compareTo(key);
                if (keyComparing == 0) {
                    lastPos = entryPos;
                    break;
                } else if (keyComparing > 0) {
                    lastPos = entryPos;
                    right = mid;
                } else {
                    left = mid;
                }
            }
            if (lastPos != -1) {
                raf.seek(lastPos);
                nextEntry = readEntry(raf);
            }

        }
        return nextEntry;
    }

    public static Entry<String> readEntry(RandomAccessFile raf) throws IOException {
        byte tombstone = raf.readByte();
        String key = raf.readUTF();
        String value = null;
        if (tombstone == 1) {
            value = raf.readUTF();
        } else if (tombstone == 2) {
            int valueSize = raf.readInt();
            byte[] valueBytes = new byte[valueSize];
            raf.read(valueBytes);
            value = new String(valueBytes, StandardCharsets.UTF_8);
        }
        return new BaseEntry<>(key, value);
    }

    public static void writeEntry(RandomAccessFile output, Entry<String> entry) throws IOException {
        String val = entry.value();
        if (val == null) {
            output.writeByte(-1);
            output.writeUTF(entry.key());
        } else {
            if (val.length() < 65536) {
                output.writeByte(1);
                output.writeUTF(entry.key());
                output.writeUTF(val);
            } else {
                output.writeByte(2);
                output.writeUTF(entry.key());
                byte[] b = val.getBytes(StandardCharsets.UTF_8);
                output.writeInt(b.length);
                output.write(b);
            }
        }
    }

    public static void removeOldFiles(Config config, List<String> filesListCopy) throws IOException {
        for (String fileToDelete : filesListCopy) {
            Files.deleteIfExists(config.basePath().resolve(fileToDelete + DATA_EXT));
            Files.deleteIfExists(config.basePath().resolve(fileToDelete + INDEX_EXT));
        }
    }

    static void writeFileListToDisk(AtomicReference<Deque<String>> filesList,
                                    RandomAccessFile allFilesOut) throws IOException {
        writeFileListLock.lock();
        try {
            // newer is at the end
            allFilesOut.setLength(0);
            Iterator<String> filesListIterator = filesList.get().descendingIterator();
            while (filesListIterator.hasNext()) {
                allFilesOut.writeUTF(filesListIterator.next());
            }
        } finally {
            writeFileListLock.unlock();
        }
    }
}
