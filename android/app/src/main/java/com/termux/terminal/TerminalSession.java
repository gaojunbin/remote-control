/*
 * Modified for Remote Control: rewritten as a session whose shell runs on another machine. The
 * subprocess, the pseudo-terminal, the reader, writer and waiter threads, the main-thread handler
 * and every JNI call are gone (and JNI.java with them). Bytes that arrive from the network are
 * handed in with feed(); bytes the emulator and the person produce leave through the Output the
 * owner sets, and so does every change of the grid's size. The methods TerminalView calls keep
 * their names and meaning; getPid(), getCwd() and the process bookkeeping, which described a
 * local process, are removed.
 */
package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

/**
 * A terminal session: an emulator coupled to a shell on the device the app is connected to.
 * <p>
 * The emulator is created by the first call to {@link #updateSize(int, int, int, int)}, when the
 * view knows how many columns and rows it holds. Bytes fed before that are kept and drawn the
 * moment it exists. All terminal emulation and callback methods run on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** Where a session's own bytes go, and what the owner is told when the grid changes. */
    public interface Output {
        /** Bytes for the shell: typed keys, pasted text and the emulator's replies to queries. */
        void onBytes(byte[] data, int offset, int count);

        /** The emulator now holds this many columns and rows. */
        void onResize(int columns, int rows);
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Buffer to write translate code points into utf8 before writing them out. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when the screen or the title changes. */
    TerminalSessionClient mClient;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    private final Integer mTranscriptRows;

    private Output mOutput;

    /** What arrived before there was an emulator to draw it. */
    private final ByteArrayOutputStream mPending = new ByteArrayOutputStream();

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Where the bytes this session produces go, and who hears of a new size. */
    public void setOutput(Output output) {
        mOutput = output;
    }

    /** Resize the emulator, or create it on the first call, and report the grid it holds. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        if (mOutput != null) mOutput.onResize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation, drawing whatever
     * arrived before it existed.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        if (mPending.size() > 0) {
            byte[] early = mPending.toByteArray();
            mPending.reset();
            mEmulator.append(early, early.length);
            notifyScreenUpdate();
        }
    }

    /** Draw bytes the shell sent. */
    public void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    /** Draw {@code length} bytes of {@code data} from {@code offset}, as the shell sent them. */
    public void feed(byte[] data, int offset, int length) {
        if (length <= 0) return;
        if (mEmulator == null) {
            mPending.write(data, offset, length);
            return;
        }
        byte[] chunk = offset == 0 ? data : Arrays.copyOfRange(data, offset, offset + length);
        mEmulator.append(chunk, length);
        notifyScreenUpdate();
    }

    /** Write data to the shell. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mOutput != null && count > 0) mOutput.onBytes(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** The shell is the device's, and ending it is the terminal protocol's business, not this view's. */
    public void finishIfRunning() {
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    /** A remote shell is running for as long as the view shows it; the screen says when it exits. */
    public synchronized boolean isRunning() {
        return true;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

}
