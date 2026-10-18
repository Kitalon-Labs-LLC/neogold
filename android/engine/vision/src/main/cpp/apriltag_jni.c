#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

// AprilTag 36h11 codebook for IDs 0, 1, 2, 3 (64-bit uint64 representations)
// Bit order: 8x8 row-major bits.
static const uint64_t TAG36H11_CODES[4] = {
    0x0010340A0C5C5600ULL, // Tag 0
    0x00485A0C1E743600ULL, // Tag 1
    0x0038104802247600ULL, // Tag 2
    0x000C264A72704E00ULL  // Tag 3
};

typedef struct {
    double x;
    double y;
} Point2D;

typedef struct {
    int id;
    Point2D corners[4]; // TL, TR, BR, BL
    Point2D center;
} DetectedTag;

static uint64_t rotate_code(uint64_t code) {
    uint64_t res = 0;
    for (int r = 0; r < 8; r++) {
        for (int c = 0; c < 8; c++) {
            int src_bit = (7 - r) * 8 + (7 - c);
            int dst_bit = (7 - c) * 8 + r; // 90 deg rotation
            if ((code >> src_bit) & 1ULL) {
                res |= (1ULL << dst_bit);
            }
        }
    }
    return res;
}

static int hamming_distance(uint64_t a, uint64_t b) {
    uint64_t x = a ^ b;
    int dist = 0;
    while (x) {
        dist += (int)(x & 1);
        x >>= 1;
    }
    return dist;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_kitalonlabs_neogold_engine_vision_AprilTagNativeDetector_nativeDetect(
    JNIEnv *env,
    jobject thiz,
    jintArray pixelsArray,
    jint width,
    jint height
) {
    if (width <= 0 || height <= 0 || pixelsArray == NULL) {
        return NULL;
    }

    jint *pixels = (*env)->GetIntArrayElements(env, pixelsArray, NULL);
    if (pixels == NULL) {
        return NULL;
    }

    // Downsample step 4 for fast candidate blob extraction
    const int step = 4;
    const int mw = width / step;
    const int mh = height / step;
    const int msize = mw * mh;

    uint8_t *darkMask = (uint8_t *)calloc(msize, sizeof(uint8_t));
    uint8_t *visited = (uint8_t *)calloc(msize, sizeof(uint8_t));
    int *queue = (int *)malloc(msize * sizeof(int));

    if (!darkMask || !visited || !queue) {
        free(darkMask);
        free(visited);
        free(queue);
        (*env)->ReleaseIntArrayElements(env, pixelsArray, pixels, JNI_ABORT);
        return NULL;
    }

    double sumLum = 0.0;
    int count = 0;
    for (int y = 0; y < mh; y++) {
        for (int x = 0; x < mw; x++) {
            jint argb = pixels[(y * step) * width + (x * step)];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            double lum = 0.299 * r + 0.587 * g + 0.114 * b;
            sumLum += lum;
            count++;
        }
    }

    double avgLum = (count > 0) ? (sumLum / count) : 128.0;
    double darkThresh = avgLum * 0.65;

    for (int y = 0; y < mh; y++) {
        for (int x = 0; x < mw; x++) {
            jint argb = pixels[(y * step) * width + (x * step)];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            double lum = 0.299 * r + 0.587 * g + 0.114 * b;
            if (lum < darkThresh) {
                darkMask[y * mw + x] = 1;
            }
        }
    }

    DetectedTag tags[16];
    int tagCount = 0;

    const int minBlob = 16;
    const int maxBlob = msize / 4;

    for (int y = 0; y < mh; y++) {
        for (int x = 0; x < mw; x++) {
            int idx = y * mw + x;
            if (darkMask[idx] && !visited[idx]) {
                int blobCount = 0;
                int minX = x, maxX = x, minY = y, maxY = y;
                int qHead = 0, qTail = 0;
                queue[qTail++] = idx;
                visited[idx] = 1;

                while (qHead < qTail) {
                    int curr = queue[qHead++];
                    int cx = curr % mw;
                    int cy = curr / mw;
                    blobCount++;

                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;

                    if (cy > 0) {
                        int n = (cy - 1) * mw + cx;
                        if (darkMask[n] && !visited[n]) { visited[n] = 1; queue[qTail++] = n; }
                    }
                    if (cy < mh - 1) {
                        int n = (cy + 1) * mw + cx;
                        if (darkMask[n] && !visited[n]) { visited[n] = 1; queue[qTail++] = n; }
                    }
                    if (cx > 0) {
                        int n = cy * mw + (cx - 1);
                        if (darkMask[n] && !visited[n]) { visited[n] = 1; queue[qTail++] = n; }
                    }
                    if (cx < mw - 1) {
                        int n = cy * mw + (cx + 1);
                        if (darkMask[n] && !visited[n]) { visited[n] = 1; queue[qTail++] = n; }
                    }
                }

                if (blobCount >= minBlob && blobCount <= maxBlob) {
                    int bw = (maxX - minX + 1) * step;
                    int bh = (maxY - minY + 1) * step;
                    double aspect = (double)bw / (bh > 0 ? bh : 1);

                    if (aspect >= 0.5 && aspect <= 2.0) {
                        // Sample 8x8 modules in the candidate box
                        double modW = (double)bw / 8.0;
                        double modH = (double)bh / 8.0;
                        int minPx = minX * step;
                        int minPy = minY * step;

                        double minSampleLum = 255.0;
                        double maxSampleLum = 0.0;
                        double gridLum[8][8];

                        for (int r = 0; r < 8; r++) {
                            for (int c = 0; c < 8; c++) {
                                int sx = (int)(minPx + (c + 0.5) * modW);
                                int sy = (int)(minPy + (r + 0.5) * modH);
                                if (sx < 0) sx = 0; if (sx >= width) sx = width - 1;
                                if (sy < 0) sy = 0; if (sy >= height) sy = height - 1;

                                jint argb = pixels[sy * width + sx];
                                int pr = (argb >> 16) & 0xFF;
                                int pg = (argb >> 8) & 0xFF;
                                int pb = argb & 0xFF;
                                double lum = 0.299 * pr + 0.587 * pg + 0.114 * pb;
                                gridLum[r][c] = lum;
                                if (lum < minSampleLum) minSampleLum = lum;
                                if (lum > maxSampleLum) maxSampleLum = lum;
                            }
                        }

                        if (maxSampleLum - minSampleLum >= 30.0) {
                            double thresh = (minSampleLum + maxSampleLum) * 0.5;
                            uint64_t code = 0;
                            for (int r = 0; r < 8; r++) {
                                for (int c = 0; c < 8; c++) {
                                    int bit = (gridLum[r][c] > thresh) ? 1 : 0;
                                    code = (code << 1) | bit;
                                }
                            }

                            // Match against TAG36H11_CODES
                            for (int candidateId = 0; candidateId < 4; candidateId++) {
                                uint64_t target = TAG36H11_CODES[candidateId];
                                uint64_t rotCode = code;
                                for (int rot = 0; rot < 4; rot++) {
                                    if (hamming_distance(rotCode, target) <= 3) {
                                        // Match found
                                        int already = 0;
                                        for (int t = 0; t < tagCount; t++) {
                                            if (tags[t].id == candidateId) { already = 1; break; }
                                        }
                                        if (!already && tagCount < 16) {
                                            tags[tagCount].id = candidateId;
                                            tags[tagCount].corners[0] = (Point2D){(double)minPx, (double)minPy};
                                            tags[tagCount].corners[1] = (Point2D){(double)(minPx + bw), (double)minPy};
                                            tags[tagCount].corners[2] = (Point2D){(double)(minPx + bw), (double)(minPy + bh)};
                                            tags[tagCount].corners[3] = (Point2D){(double)minPx, (double)(minPy + bh)};
                                            tags[tagCount].center = (Point2D){minPx + bw * 0.5, minPy + bh * 0.5};
                                            tagCount++;
                                        }
                                        break;
                                    }
                                    rotCode = rotate_code(rotCode);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    free(darkMask);
    free(visited);
    free(queue);
    (*env)->ReleaseIntArrayElements(env, pixelsArray, pixels, JNI_ABORT);

    // Format output: [count, id, x0, y0, x1, y1, x2, y2, x3, y3, cx, cy, ...]
    int outLen = 1 + tagCount * 11;
    jdoubleArray outArray = (*env)->NewDoubleArray(env, outLen);
    if (outArray == NULL) {
        return NULL;
    }

    jdouble *buf = (jdouble *)malloc(outLen * sizeof(jdouble));
    if (!buf) {
        return NULL;
    }

    buf[0] = (double)tagCount;
    int off = 1;
    for (int i = 0; i < tagCount; i++) {
        buf[off++] = (double)tags[i].id;
        buf[off++] = tags[i].corners[0].x;
        buf[off++] = tags[i].corners[0].y;
        buf[off++] = tags[i].corners[1].x;
        buf[off++] = tags[i].corners[1].y;
        buf[off++] = tags[i].corners[2].x;
        buf[off++] = tags[i].corners[2].y;
        buf[off++] = tags[i].corners[3].x;
        buf[off++] = tags[i].corners[3].y;
        buf[off++] = tags[i].center.x;
        buf[off++] = tags[i].center.y;
    }

    (*env)->SetDoubleArrayRegion(env, outArray, 0, outLen, buf);
    free(buf);

    return outArray;
}
