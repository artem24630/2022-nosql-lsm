package ru.mail.polis.artyomtrofimov;

import ru.mail.polis.BaseEntry;
import ru.mail.polis.Config;
import ru.mail.polis.Dao;
import ru.mail.polis.Entry;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class InMemoryDao implements Dao<String, Entry<String>> {
    public static final String DATA_EXT = ".dat";
    public static final String INDEX_EXT = ".ind";
    private static final String ALL_FILES = "files.fl";
    private static final Random rnd = new Random();
    private final ConcurrentNavigableMap<String, Entry<String>> data = new ConcurrentSkipListMap<>();
    private final Path basePath;
    private volatile boolean commit;
    private final Deque<String> filesList = new ArrayDeque<>();

    public InMemoryDao(Config config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("Config shouldn't be null");
        }
        basePath = config.basePath();
        loadFilesList();
    }

    private static String generateString() {
        char[] chars = new char[rnd.nextInt(8, 9)];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (rnd.nextInt('z' - '0') + '0');
        }
        return new String(chars);
    }

    private static Entry<String> findInFileByKey(String key, Path basePath, String name) throws IOException {
        if (name == null) {
            return null;
        }
        Path dataPath = basePath.resolve(name + DATA_EXT);
        Path indexPath = basePath.resolve(name + INDEX_EXT);
        try (RandomAccessFile input = new RandomAccessFile(dataPath.toString(), "r");
             RandomAccessFile indexInput = new RandomAccessFile(indexPath.toString(), "r")) {
            input.seek(0);
            int size = input.readInt();
            long left = 0;
            long right = size;
            long mid;
            while (left < right) {
                mid = left + (right - left) / 2;
                indexInput.seek(mid * Long.BYTES);
                long index = indexInput.readLong();
                input.seek(index & 0x7fffffff);
                String currentKey = input.readUTF();
                int keyComparing = key.compareTo(currentKey);
                if (keyComparing == 0) {
                    return new BaseEntry<>(currentKey, index < 0 ? null : input.readUTF());
                } else if (keyComparing < 0) {
                    right = mid;
                } else {
                    left = mid;
                }
            }
            return null;
        } catch (FileNotFoundException | EOFException e) {
            return null;
        }
    }

    @Override
    public Iterator<Entry<String>> get(String from, String to) throws IOException {
        if (from == null) {
            from = "";
        }
        Iterator<Entry<String>> dataIterator;
        if (to == null) {
            dataIterator = data.tailMap(from).values().iterator();
        } else {
            dataIterator = data.subMap(from, to).values().iterator();
        }
        List<PeekingIterator> iterators = new ArrayList<>();
        iterators.add(new PeekingIterator(dataIterator));
        for (String file : filesList) {
            iterators.add(new PeekingIterator(new FileIterator(basePath, file, from, to)));
        }
        return new MergeIterator(iterators);
    }

    @Override
    public Entry<String> get(String key) throws IOException {
        Entry<String> entry = data.get(key);
        if (entry == null) {
            for (String file : filesList) {
                entry = findInFileByKey(key, basePath, file);
                if (entry != null) {
                    break;
                }
            }
        }
        return entry;
    }

    @Override
    public void upsert(Entry<String> entry) {
        data.put(entry.key(), entry);
        commit = false;
    }

    private void loadFilesList() throws IOException {
        try (RandomAccessFile reader = new RandomAccessFile(basePath.resolve(ALL_FILES).toString(), "r")) {
            while (reader.getFilePointer() < reader.length()) {
                String file = reader.readUTF();
                filesList.addFirst(file);
            }
        } catch (NoSuchFileException | EOFException | FileNotFoundException ignored) {
            //it is ok
        }
    }

    @Override
    public void flush() throws IOException {
        if (commit) {
            return;
        }
        String name = generateString();
        Path file = basePath.resolve(name + DATA_EXT);
        Path index = basePath.resolve(name + INDEX_EXT);
        filesList.addFirst(name);

        try (RandomAccessFile output = new RandomAccessFile(file.toString(), "rw");
             RandomAccessFile indexOut = new RandomAccessFile(index.toString(), "rw");
             RandomAccessFile allFilesOut = new RandomAccessFile(basePath.resolve(ALL_FILES).toString(), "rw")
        ) {
            output.seek(0);
            output.writeInt(data.size());
            for (Entry<String> value : data.values()) {
                long fp = output.getFilePointer();
                String val = value.value();
                if (val == null) {
                    fp = -fp;
                    val = "";
                }
                indexOut.writeLong(fp);
                output.writeUTF(value.key());
                output.writeUTF(val);
            }
            allFilesOut.setLength(0);
            while (!filesList.isEmpty()) {
                allFilesOut.writeUTF(filesList.pollLast());
            }
        }
        commit = true;
    }
}
