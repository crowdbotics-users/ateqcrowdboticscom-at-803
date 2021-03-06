package com.reactlibrary.recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class SoundFile {
    // Member variables for hack (making it work with old version, until app just uses the samples).
    public List<Double> pixelGains;
    private ProgressListener mProgressListener = null;
    private File mInputFile = null;
    // Member variables representing frame data
    private String mFileType;
    private int mFileSize;
    private int mAvgBitRate;  // Average bit rate in kbps.
    private int mSampleRate;
    private int mChannels;
    private int mNumSamples;  // total number of samples per channel in audio file
    private ByteBuffer mDecodedBytes;  // Raw audio data
    // mDecodedSamples has the following format:
    // {s1c1, s1c2, ..., s1cM, s2c1, ..., s2cM, ..., sNc1, ..., sNcM}
    // where sicj is the ith sample of the jth channel (a sample is a signed short)
    // M is the number of channels (e.g. 2 for stereo) and N is the number of samples per channel.
    private ShortBuffer mDecodedSamples;  // shared buffer with mDecodedBytes.
    private int mSamplesPerPixel = 100;
    private int mPixelsPerSec = 100;
    private double mLastGain = 0;
    private int mLastSamples = 0;

    // A SoundFile object should only be created using the static methods create() and record().
    private SoundFile() {
        pixelGains = new LinkedList<>();
    }

    // TODO(nfaralli): what is the real list of supported extensions? Is it device dependent?
    public static String[] getSupportedExtensions() {
        return new String[]{"mp3", "wav", "3gpp", "3gp", "amr", "aac", "m4a", "ogg"};
    }

    public static boolean isFilenameSupported(String filename) {
        String[] extensions = getSupportedExtensions();
        for (int i = 0; i < extensions.length; i++) {
            if (filename.endsWith("." + extensions[i])) {
                return true;
            }
        }
        return false;
    }

    // Create and return a SoundFile object using the file fileName.
    public static SoundFile create(String fileName,
                                   int pixelsPerSec,
                                   int fromInMs,
                                   int toInMs,
                                   ProgressListener progressListener)
            throws java.io.FileNotFoundException,
            java.io.IOException, InvalidInputException {
        // First check that the file exists and that its extension is supported.
        File f = new File(fileName);
        if (!f.exists()) {
            throw new java.io.FileNotFoundException(fileName);
        }
        String name = f.getName().toLowerCase();
        String[] components = name.split("\\.");
        if (components.length < 2) {
            throw new InvalidInputException("Invalid Path" + fileName);
        }
        if (!Arrays.asList(getSupportedExtensions()).contains(components[components.length - 1])) {
            throw new InvalidInputException("This file format doesn't support." + fileName);
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.setPixelsPerSec(pixelsPerSec);
        soundFile.ReadFile(f, fromInMs, toInMs);
        return soundFile;
    }

    // Create and return a SoundFile object by recording a mono audio stream.
    public static SoundFile createRecord(int pixelsPerSec, ProgressListener progressListener) {
        if (progressListener == null) {
            // must have a progessListener to stop the recording.
            return null;
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.initForRecord();
        soundFile.setPixelsPerSec(pixelsPerSec);
        return soundFile;
    }

    static public boolean compress(String inputPath, String outPath) throws
            java.io.IOException, InvalidInputException, IllegalStateException {

        // define Files fro input and output files
        File inputFile = new File(inputPath);

        // Extract from input file
        MediaExtractor extractor = new MediaExtractor();
        MediaFormat inputFormat = null;
        extractor.setDataSource(inputFile.getPath());
        int numTracks = extractor.getTrackCount();

        // find and select the first audio track present in the file.
        int inputFileSize = (int) inputFile.length();
        int i;
        for (i = 0; i < numTracks; i++) {
            inputFormat = extractor.getTrackFormat(i);
            if (inputFormat.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                extractor.selectTrack(i);
                break;
            }
        }
        if (i == numTracks) {
            throw new InvalidInputException("No audio track found in " + inputFile);
        }

        int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);

        // Expected total number of samples per channel.
        int expectedNumSamples =
                (int) ((inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000000.f) * sampleRate + 0.5f);

        // Decoder from input file
        MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();


        // Set up Encoder
        // Some devices have problems reading mono AAC files (e.g. Samsung S3). Making it stereo.
        int numChannels = (channels == 1) ? 2 : channels;

        String mimeType = "audio/mp4a-latm";
        int bitrate = 96000;
        MediaCodec encoder = MediaCodec.createEncoderByType(mimeType);
        MediaFormat outputFormat = MediaFormat.createAudioFormat(mimeType, sampleRate, numChannels);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo info2 = new MediaCodec.BufferInfo();

        // Set up muxer
        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int audioTrack = muxer.addTrack(encoder.getOutputFormat());
        muxer.start();

        // Temp variables
        int frame_size = 1024;  // number of samples per frame per channel for an mp4 (AAC) stream.
        byte buffer[] = new byte[frame_size * numChannels * 2];  // a sample is coded with a short.
        int decodedSamplesSize = 0;  // size of the output buffer containing decoded samples.
        byte[] decodedSamples = null;
        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        int sample_size;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long presentation_time;
        int tot_size_read = 0;
        boolean done_reading = false;

        // Set the size of the decoded samples buffer to 1MB (~6sec of a stereo stream at 44.1kHz).
        // For longer streams, the buffer size will be increased later on, calculating a rough
        // estimate of the total size needed to store all the samples in order to resize the buffer
        // only once.
        ByteBuffer decodedBytes = ByteBuffer.allocate(1 << 20);
        Boolean firstSampleData = true;
        while (true) {
            // read data from file and feed it to the decoder input buffers.
            int inputBufferIndex = decoder.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                sample_size = extractor.readSampleData(inputBuffers[inputBufferIndex], 0);
                if (firstSampleData
                        && inputFormat.getString(MediaFormat.KEY_MIME).equals("audio/mp4a-latm")
                        && sample_size == 2) {
                    // For some reasons on some devices (e.g. the Samsung S3) you should not
                    // provide the first two bytes of an AAC stream, otherwise the MediaCodec will
                    // crash. These two bytes do not contain music data but basic info on the
                    // stream (e.g. channel configuration and sampling frequency), and skipping them
                    // seems OK with other devices (MediaCodec has already been configured and
                    // already knows these parameters).
                    extractor.advance();
                    tot_size_read += sample_size;
                } else if (sample_size < 0) {
                    // All samples have been read.
                    decoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    presentation_time = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputBufferIndex, 0, sample_size, presentation_time, 0);
                    extractor.advance();
                    tot_size_read += sample_size;
                }
                firstSampleData = false;
            }

            // Get decoded stream from the decoder output buffers.
            int outputBufferIndex = decoder.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0) {
                if (decodedSamplesSize < info.size) {
                    decodedSamplesSize = info.size;
                    decodedSamples = new byte[decodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(decodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                // Check if buffer is big enough. Resize it if it's too small.
                if (decodedBytes.remaining() < info.size) {
                    // Getting a rough estimate of the total size, allocate 20% more, and
                    // make sure to allocate at least 5MB more than the initial size.
                    int position = decodedBytes.position();
                    int newSize = (int) ((position * (1.0 * inputFileSize / tot_size_read)) * 1.2);
                    if (newSize - position < info.size + 5 * (1 << 20)) {
                        newSize = position + info.size + 5 * (1 << 20);
                    }
                    ByteBuffer newDecodedBytes = null;
                    // Try to allocate memory. If we are OOM, try to run the garbage collector.
                    int retry = 10;
                    while (retry > 0) {
                        try {
                            newDecodedBytes = ByteBuffer.allocate(newSize);
                            break;
                        } catch (OutOfMemoryError oome) {
                            // setting android:largeHeap="true" in <application> seem to help not
                            // reaching this section.
                            retry--;
                        }
                    }
                    if (retry == 0) {
                        // Failed to allocate memory... Stop reading more data and finalize the
                        // instance with the data decoded so far.
                        break;
                    }
                    //ByteBuffer newDecodedBytes = ByteBuffer.allocate(newSize);
                    decodedBytes.rewind();
                    newDecodedBytes.put(decodedBytes);
                    decodedBytes = newDecodedBytes;
                    decodedBytes.position(position);
                }
                decodedBytes.put(decodedSamples, 0, info.size);
                decoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = decoder.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Subsequent data will conform to new format.
                // We could check that codec.getOutputFormat(), which is the new output format,
                // is what we expect.
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    || (decodedBytes.position() / (2 * channels)) >= expectedNumSamples) {
                // We got all the decoded data from the decoder. Stop here.
                // Theoretically dequeueOutputBuffer(info, ...) should have set info.flags to
                // MediaCodec.BUFFER_FLAG_END_OF_STREAM. However some phones (e.g. Samsung S3)
                // won't do that for some files (e.g. with mono AAC files), in which case subsequent
                // calls to dequeueOutputBuffer may result in the application crashing, without
                // even an exception being thrown... Hence the second check.
                // (for mono AAC files, the S3 will actually double each sample, as if the stream
                // was stereo. The resulting stream is half what it's supposed to be and with a much
                // lower pitch.)
                break;
            }
        }
        int numSamples = decodedBytes.position() / (channels * 2);  // One sample = 2 bytes.

        done_reading = false;
        decodedBytes.position(0);
        numSamples += (2 * frame_size);  // Adding 2 frames, Cf. priming frames for AAC.

        int num_frames = 0;
        int num_samples_left = numSamples;


        while (true) {
            // Feed the samples to the encoder.
            int inputBufferIndex = encoder.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                if (num_samples_left <= 0) {
                    // All samples have been read.
                    encoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    encoderInputBuffers[inputBufferIndex].clear();
                    if (buffer.length > encoderInputBuffers[inputBufferIndex].remaining()) {
                        // Input buffer is smaller than one frame. This should never happen.
                        continue;
                    }
                    // bufferSize is a hack to create a stereo file from a mono stream.
                    int bufferSize = (channels == 1) ? (buffer.length / 2) : buffer.length;
                    if (decodedBytes.remaining() < bufferSize) {
                        for (i = decodedBytes.remaining(); i < bufferSize; i++) {
                            buffer[i] = 0;  // pad with extra 0s to make a full frame.
                        }
                        decodedBytes.get(buffer, 0, decodedBytes.remaining());
                    } else {
                        decodedBytes.get(buffer, 0, bufferSize);
                    }
                    if (channels == 1) {
                        for (i = bufferSize - 1; i >= 1; i -= 2) {
                            buffer[2 * i + 1] = buffer[i];
                            buffer[2 * i] = buffer[i - 1];
                            buffer[2 * i - 1] = buffer[2 * i + 1];
                            buffer[2 * i - 2] = buffer[2 * i];
                        }
                    }
                    num_samples_left -= frame_size;
                    encoderInputBuffers[inputBufferIndex].put(buffer);
                    presentation_time = (long) (((num_frames++) * frame_size * 1e6) / sampleRate);
                    encoder.queueInputBuffer(
                            inputBufferIndex, 0, buffer.length, presentation_time, 0);
                }
            }

            // Get the encoded samples from the encoder.
            int outputBufferIndex = encoder.dequeueOutputBuffer(info2, 100);
            if (outputBufferIndex >= 0 && info2.size > 0 && info2.presentationTimeUs >= 0) {
                muxer.writeSampleData(audioTrack, encoderOutputBuffers[outputBufferIndex], info2);
                encoderOutputBuffers[outputBufferIndex].clear();
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Subsequent data will conform to new format.
                // We could check that codec.getOutputFormat(), which is the new output format,
                // is what we expect.
            }
            if ((info2.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // We got all the encoded data from the encoder.
                break;
            }
        }

        extractor.release();
        decoder.stop();
        decoder.release();
        encoder.stop();
        encoder.release();

        muxer.stop();
        muxer.release();

        return true;
    }

    public String getFiletype() {
        return mFileType;
    }

    public int getFileSizeBytes() {
        return mFileSize;
    }

    public int getAvgBitrateKbps() {
        return mAvgBitRate;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannels() {
        return mChannels;
    }

    public int getNumSamples() {
        return mNumSamples;  // Number of samples per channel.
    }

    // Should be removed when the app will use directly the samples instead of the frames.
    public int getNumPixels() {
        return pixelGains.size();
    }

    // Should be removed when the app will use directly the samples instead of the frames.
    public int getSamplesPerFrame() {
        return 1024;  // just a fixed value here...
    }

    public int getSamplesPerPixel() {
        return mSamplesPerPixel;
    }

    public void setPixelsPerSec(int pixelsPerSec) {
        mPixelsPerSec = pixelsPerSec;
        mSamplesPerPixel = mSampleRate / pixelsPerSec;
    }

    public ShortBuffer getSamples() {
        if (mDecodedSamples != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                // Hack for Nougat where asReadOnlyBuffer fails to respect byte ordering.
                // See https://code.google.com/p/android/issues/detail?id=223824
                return mDecodedSamples;
            } else {
                return mDecodedSamples.asReadOnlyBuffer();
            }
        } else {
            return null;
        }
    }

    private void setProgressListener(ProgressListener progressListener) {
        mProgressListener = progressListener;
    }

    private void ReadFile(File inputFile, int fromInMs, int toInMs)
            throws java.io.FileNotFoundException,
            java.io.IOException, InvalidInputException {
        MediaExtractor extractor = new MediaExtractor();
        MediaFormat format = null;
        int i;

        mInputFile = inputFile;
        String[] components = mInputFile.getPath().split("\\.");
        mFileType = components[components.length - 1];
        mFileSize = (int) mInputFile.length();
        extractor.setDataSource(mInputFile.getPath());
        int numTracks = extractor.getTrackCount();
        // find and select the first audio track present in the file.
        for (i = 0; i < numTracks; i++) {
            format = extractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                extractor.selectTrack(i);
                break;
            }
        }
        if (i == numTracks) {
            throw new InvalidInputException("No audio track found in " + mInputFile);
        }
        mChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);

        mSamplesPerPixel = mSampleRate / mPixelsPerSec;
        // Expected total number of samples per channel.
        int expectedNumSamples =
                (int) ((format.getLong(MediaFormat.KEY_DURATION) / 1000000.f) * mSampleRate + 0.5f);

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        int decodedSamplesSize = 0;  // size of the output buffer containing decoded samples.
        byte[] decodedSamples = null;
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        int sample_size;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long presentation_time;
        int tot_size_read = 0;
        boolean done_reading = false;

        // Set the size of the decoded samples buffer to 1MB (~6sec of a stereo stream at 44.1kHz).
        // For longer streams, the buffer size will be increased later on, calculating a rough
        // estimate of the total size needed to store all the samples in order to resize the buffer
        // only once.
        mDecodedBytes = ByteBuffer.allocate(1 << 20);
        Boolean firstSampleData = true;
        while (true) {
            // read data from file and feed it to the decoder input buffers.
            int inputBufferIndex = codec.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                sample_size = extractor.readSampleData(inputBuffers[inputBufferIndex], 0);
                if (firstSampleData
                        && format.getString(MediaFormat.KEY_MIME).equals("audio/mp4a-latm")
                        && sample_size == 2) {
                    // For some reasons on some devices (e.g. the Samsung S3) you should not
                    // provide the first two bytes of an AAC stream, otherwise the MediaCodec will
                    // crash. These two bytes do not contain music data but basic info on the
                    // stream (e.g. channel configuration and sampling frequency), and skipping them
                    // seems OK with other devices (MediaCodec has already been configured and
                    // already knows these parameters).
                    extractor.advance();
                    tot_size_read += sample_size;
                } else if (sample_size < 0) {
                    // All samples have been read.
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    presentation_time = extractor.getSampleTime();
                    codec.queueInputBuffer(inputBufferIndex, 0, sample_size, presentation_time, 0);
                    extractor.advance();
                    tot_size_read += sample_size;
                }
                firstSampleData = false;
            }

            // Get decoded stream from the decoder output buffers.
            int outputBufferIndex = codec.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0) {
                if (decodedSamplesSize < info.size) {
                    decodedSamplesSize = info.size;
                    decodedSamples = new byte[decodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(decodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                // Check if buffer is big enough. Resize it if it's too small.
                if (mDecodedBytes.remaining() < info.size) {
                    // Getting a rough estimate of the total size, allocate 20% more, and
                    // make sure to allocate at least 5MB more than the initial size.
                    int position = mDecodedBytes.position();
                    int newSize = (int) ((position * (1.0 * mFileSize / tot_size_read)) * 1.2);
                    if (newSize - position < info.size + 5 * (1 << 20)) {
                        newSize = position + info.size + 5 * (1 << 20);
                    }
                    ByteBuffer newDecodedBytes = null;
                    // Try to allocate memory. If we are OOM, try to run the garbage collector.
                    int retry = 10;
                    while (retry > 0) {
                        try {
                            newDecodedBytes = ByteBuffer.allocate(newSize);
                            break;
                        } catch (OutOfMemoryError oome) {
                            // setting android:largeHeap="true" in <application> seem to help not
                            // reaching this section.
                            retry--;
                        }
                    }
                    if (retry == 0) {
                        // Failed to allocate memory... Stop reading more data and finalize the
                        // instance with the data decoded so far.
                        break;
                    }
                    //ByteBuffer newDecodedBytes = ByteBuffer.allocate(newSize);
                    mDecodedBytes.rewind();
                    newDecodedBytes.put(mDecodedBytes);
                    mDecodedBytes = newDecodedBytes;
                    mDecodedBytes.position(position);
                }
                mDecodedBytes.put(decodedSamples, 0, info.size);
                codec.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Subsequent data will conform to new format.
                // We could check that codec.getOutputFormat(), which is the new output format,
                // is what we expect.
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    || (mDecodedBytes.position() / (2 * mChannels)) >= expectedNumSamples) {
                // We got all the decoded data from the decoder. Stop here.
                // Theoretically dequeueOutputBuffer(info, ...) should have set info.flags to
                // MediaCodec.BUFFER_FLAG_END_OF_STREAM. However some phones (e.g. Samsung S3)
                // won't do that for some files (e.g. with mono AAC files), in which case subsequent
                // calls to dequeueOutputBuffer may result in the application crashing, without
                // even an exception being thrown... Hence the second check.
                // (for mono AAC files, the S3 will actually double each sample, as if the stream
                // was stereo. The resulting stream is half what it's supposed to be and with a much
                // lower pitch.)
                break;
            }
        }
        mNumSamples = mDecodedBytes.position() / (mChannels * 2);  // One sample = 2 bytes.
        if (fromInMs != -1 ||
                toInMs != -1) {
            int startSamples = 0;
            if (fromInMs > 0) {
                startSamples = mSampleRate * fromInMs / 1000;
            }

            int endSamples = mNumSamples;
            if (toInMs > 0) {
                endSamples = Math.min(mSampleRate * toInMs / 1000, mNumSamples);
            }
            if (endSamples == mNumSamples) {
                mNumSamples = startSamples;
            } else {
                ByteBuffer newByteBuffer = ByteBuffer.allocate(mDecodedBytes.capacity());
                newByteBuffer.put(mDecodedBytes.array(),
                        0,
                        startSamples * mChannels * 2);
                newByteBuffer.put(mDecodedBytes.array(),
                        endSamples * mChannels * 2,
                        (mNumSamples - endSamples) * mChannels * 2);
                mNumSamples = newByteBuffer.position() / mChannels / 2;
                mDecodedBytes = newByteBuffer;
            }
        }
        mDecodedBytes.rewind();
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        mAvgBitRate = (int) ((mFileSize * 8) * ((float) mSampleRate / mNumSamples) / 1000);

        extractor.release();
        extractor = null;
        codec.stop();
        codec.release();
        codec = null;

        // Temporary hack to make it work with the old version.

        mLastGain = 0;
        mLastSamples = 0;
        short value = 0;
        mDecodedSamples.rewind();
        while (mDecodedSamples.position() < mNumSamples * mChannels) {
            for (int k = 0; k < mChannels; k++) {
                if (mDecodedSamples.remaining() > 0) {
                    value += java.lang.Math.abs(mDecodedSamples.get());
                }
            }
            value /= mChannels;
            mLastGain = Math.max(mLastGain, value);
            mLastSamples++;
            value = 0;
            if (mLastSamples < mSamplesPerPixel) continue;
            pixelGains.add(mLastGain);
            mLastGain = 0;
            mLastSamples = 0;
        }
    }

    private void initForRecord() {
        if (mProgressListener == null) {
            // A progress listener is mandatory here, as it will let us know when to stop recording.
            return;
        }
        mInputFile = null;
        mFileType = "raw";
        mFileSize = 0;
        mSampleRate = 16000;
        mChannels = 1;  // record mono audio.
        mDecodedBytes = ByteBuffer.allocate(20 * mSampleRate * 2);
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        mLastSamples = 0;
        mLastGain = 0;
    }

    public void RecordAudio(long offsetInMs) {
        int samplesForPos = (int) (mSampleRate / 1000 * offsetInMs);
        int currentGainIndex = Math.max(pixelGains.size() - 1, 0);
        if (samplesForPos < mNumSamples) {
            // remove graph data
            currentGainIndex = samplesForPos / mSamplesPerPixel;
            if (currentGainIndex < pixelGains.size()) {
                mLastGain = pixelGains.get(currentGainIndex);
                mLastSamples = samplesForPos % mSamplesPerPixel;
            }
            // change position
            mDecodedSamples.position(samplesForPos * mChannels);
        }

        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[1024];  // buffer contains 1 mono frame of 1024 16 bits samples
        int channelConfig = (mChannels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
        int minBufferSize = AudioRecord.getMinBufferSize(
                mSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        // make sure minBufferSize can contain at least 1 second of audio (16 bits sample).
        if (minBufferSize < mSampleRate * 2) {
            minBufferSize = mSampleRate * 2;
        }
        AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                mSampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
        );

        // Allocate memory for 20 seconds first. Reallocate later if more is needed.
        audioRecord.startRecording();
        while (true) {
            // check if mDecodedSamples can contain 1024 additional samples.
            if (mDecodedSamples.remaining() < 1024) {
                // Try to allocate memory for 10 additional seconds.
                int newCapacity = mDecodedBytes.capacity() + 10 * mSampleRate * 2;
                ByteBuffer newDecodedBytes = null;
                try {
                    newDecodedBytes = ByteBuffer.allocate(newCapacity);
                } catch (OutOfMemoryError oome) {
                    break;
                }
                int position = mDecodedSamples.position();
                mDecodedBytes.rewind();
                newDecodedBytes.put(mDecodedBytes);
                mDecodedBytes = newDecodedBytes;
                mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
                mDecodedBytes.rewind();
                mDecodedSamples = mDecodedBytes.asShortBuffer();
                mDecodedSamples.position(position);
            }

            int size = audioRecord.read(buffer, 0, buffer.length);
            mDecodedSamples.put(buffer);
            // Let the progress listener know how many seconds have been recorded.
            // The returned value tells us if we should keep recording or stop.

            int value = 0;
            for (int i = 0; i < buffer.length; i++) {
                value += java.lang.Math.abs(buffer[i]);
                for (int k = 1; k < mChannels; k++) {
                    i++;
                    if (i < buffer.length) {
                        value += java.lang.Math.abs(buffer[i]);
                    }
                }
                value /= mChannels;
                mLastGain = Math.max(mLastGain, value);
                mLastSamples++;
                value = 0;
                if (mLastSamples < mSamplesPerPixel) continue;
                if (currentGainIndex < pixelGains.size()) {
                    pixelGains.set(currentGainIndex, mLastGain);
                } else {
                    pixelGains.add(mLastGain);
                }
                currentGainIndex++;
                mLastGain = 0;
                mLastSamples = 0;
            }
            if (!mProgressListener.reportProgress(
                    (double) (mDecodedSamples.position()) / mSampleRate / mChannels)) {
                break;
            }
        }
        audioRecord.stop();
        audioRecord.release();
        int numSamples = mDecodedSamples.position() / mChannels;
        if (mNumSamples < numSamples) {
            mNumSamples = numSamples;
        }
        mAvgBitRate = mSampleRate * 16 / 1000;
    }

    public void truncateFile(long positionInMs) {
        int samplesForPos = (int) (mSampleRate * positionInMs / 1000);
        if (samplesForPos >= mNumSamples) return;
        // remove graph data
        int remainGains = samplesForPos / mSamplesPerPixel;
        if (remainGains < pixelGains.size()) {
            mLastGain = pixelGains.get(remainGains);
            mLastSamples = samplesForPos % mSamplesPerPixel;
            while (pixelGains.size() > remainGains) {
                pixelGains.remove(pixelGains.size() - 1);
            }
        }
        // change position and length

        mDecodedSamples.position(samplesForPos * mChannels);
        mNumSamples = samplesForPos;
    }

    public void WriteWAVFile(File outputFile)
            throws java.io.IOException {
        int startOffset = 0;
        int numSamples = mNumSamples;

        // Start by writing the RIFF header.
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        outputStream.write(WAVHeader.getWAVHeader(mSampleRate, mChannels, numSamples));

        // Write the samples to the file, 1024 at a time.
        byte buffer[] = new byte[1024 * mChannels * 2];  // Each sample is coded with a short.
        mDecodedBytes.position(startOffset);
        int numBytesLeft = numSamples * mChannels * 2;
        while (numBytesLeft >= buffer.length) {
            if (mDecodedBytes.remaining() < buffer.length) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < buffer.length; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer);
            numBytesLeft -= buffer.length;
        }
        if (numBytesLeft > 0) {
            if (mDecodedBytes.remaining() < numBytesLeft) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < numBytesLeft; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer, 0, numBytesLeft);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer, 0, numBytesLeft);
        }
        outputStream.close();
    }

    // Method used to swap the left and right channels (needed for stereo WAV files).
    // buffer contains the PCM data: {sample 1 right, sample 1 left, sample 2 right, etc.}
    // The size of a sample is assumed to be 16 bits (for a single channel).
    // When done, buffer will contain {sample 1 left, sample 1 right, sample 2 left, etc.}
    private void swapLeftRightChannels(byte[] buffer) {
        byte left[] = new byte[2];
        byte right[] = new byte[2];
        if (buffer.length % 4 != 0) {  // 2 channels, 2 bytes per sample (for one channel).
            // Invalid buffer size.
            return;
        }
        for (int offset = 0; offset < buffer.length; offset += 4) {
            left[0] = buffer[offset];
            left[1] = buffer[offset + 1];
            right[0] = buffer[offset + 2];
            right[1] = buffer[offset + 3];
            buffer[offset] = right[0];
            buffer[offset + 1] = right[1];
            buffer[offset + 2] = left[0];
            buffer[offset + 3] = left[1];
        }
    }

    // Progress listener interface.
    public interface ProgressListener {
        /**
         * Will be called by the SoundFile class periodically
         * with values between 0.0 and 1.0.  Return true to continue
         * loading the file or recording the audio, and false to cancel or stop recording.
         */
        boolean reportProgress(double fractionComplete);
    }

    // Custom exception for invalid inputs.
    public static class InvalidInputException extends Exception {
        // Serial version ID generated by Eclipse.
        private static final long serialVersionUID = -2505698991597837165L;

        public InvalidInputException(String message) {
            super(message);
        }
    }
}