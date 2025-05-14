package BitboardMoveGen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static Board.BitboardBoard.*;

public class LookupTableGeneration {

    public static long[] generateKingLookupTable() {

        long[] moves = new long[64];

        int[] offsets = {1, -1, 8, -8, 7, -7, 9, -9};

        for (int i = 0; i < 64; i++) {
            long king = 1L << i;
            long validMoves = 0L;

            for (int offset : offsets) {
                int targetSquare = i + offset;
                if (targetSquare >= 0 && targetSquare < 64) { // Check to see if the move goes out of bounds.
                    long move = king << offset;
                    validMoves |= move;
                }
            }
            moves[i] = validMoves;
        }

        return moves;
    }

    public static long[] generateKnightLookupTable() {

        long[] moves = new long[64];

        int[] offsets = {15, 17, -15, -17, 6, 10, -6, -10};

        for (int i = 0; i < 64; i++) {
            long knight = 1L << i;
            long validMoves = 0L;

            for (int offset : offsets) {
                int targetSquare = i + offset;
                if (targetSquare >= 0 && targetSquare < 64) { // Check to see if the move goes out of bounds.
                    long move = offset > 0 ? (knight << offset) : (knight >>> -offset);
                    validMoves |= move;
                }
            }
            moves[i] = validMoves;
        }

        return moves;
    }

    // ##########################################################################

    public static long[] generateBishopMasks() {
        long[] masks = new long[64];
        for (int square = 0; square < 64; square++) {
            masks[square] = generateBishopMask(square);
        }
        return masks;
    }

    public static long[] generateRookMasks() {
        long[] masks = new long[64];
        for (int square = 0; square < 64; square++) {
            masks[square] = generateRookMask(square);
        }
        return masks;
    }

    private static long generateRookMask(int square) {
        long mask = 0L;
        int rank = square / 8;
        int file = square % 8;

        // For each direction, we do an 'EQUALS OR' operation for each square after shifting it to add it to the mask.

        // Up.
        for (int r = rank + 1; r <= 6; r++) {
            mask |= 1L << (r * 8 + file);
        }
        // Down.
        for (int r = rank - 1; r >= 1; r--) {
            mask |= 1L << (r * 8 + file);
        }
        // Right.
        for (int f = file + 1; f <= 6; f++) {
            mask |= 1L << (rank * 8 + f);
        }
        // Left.
        for (int f = file - 1; f >= 1; f--) {
            mask |= 1L << (rank * 8 + f);
        }

        return mask;
    }

    private static long generateBishopMask(int square) {
        long mask = 0L;
        int rank = square / 8;
        int file = square % 8;

        // Second verse, same as the first, but diagonally (duh).

        // Upper right, upper left, lower right, lower left, in that order.
        for (int r = rank + 1, f = file + 1; r <= 6 && f <= 6; r++, f++) {
            mask |= 1L << (r * 8 + f);
        }
        for (int r = rank + 1, f = file - 1; r <= 6 && f >= 1; r++, f--) {
            mask |= 1L << (r * 8 + f);
        }
        for (int r = rank - 1, f = file + 1; r >= 1 && f <= 6; r--, f++) {
            mask |= 1L << (r * 8 + f);
        }
        for (int r = rank - 1, f = file - 1; r >= 1 && f >= 1; r--, f--) {
            mask |= 1L << (r * 8 + f);
        }

        return mask;
    }

    public static long[][] enumerateAllBlockerBitboards(long[] masks) {
        long[][] blockerBitboards = new long[64][];
        for (int i = 0; i < 64; i++) {
            blockerBitboards[i] = enumerateBlockerBitboards(masks[i]);
        }
        return blockerBitboards;

    }

    private static long[] enumerateBlockerBitboards(long mask) {
        List<Integer> bitIndices = new ArrayList<>();

        // Anyway, first we get the indices of the bits in the mask.

        for (int i = 0; i < 64; i++) {
            if (((mask >>> i) & 1L) != 0) {
                bitIndices.add(i);
            }
        }

        int bits = bitIndices.size();
        int combinations = 1 << bits; // Bitwise left shift, equivalent to 2^bits.
        long[] result = new long[combinations]; // Setting the initial capacity for efficiency reasons.

        // And then we just iterate through *all* the possible combinations of blockers.

        for (int i = 0; i < combinations; i++) {
            long blocker = 0L;
            for (int j = 0; j < bits; j++) {
                if (((i >>> j) & 1) != 0) {
                    blocker |= 1L << bitIndices.get(j);
                }
            }
            result[i] = blocker;
        }

        return result;
    }

    public static long[][] generateRookAttacks(long[][] blockerBoards) {
        long[][] attacks = new long[64][];
        for (int i = 0; i < 64; i++) {
            attacks[i] = new long[blockerBoards[i].length];
            for (int j = 0; j < blockerBoards[i].length; j++) {
                attacks[i][j] = getRookAttack(i, blockerBoards[i][j]);
            }
        }
        return attacks;
    }

    public static long getRookAttack(int square, long occupancy) {
        long attacks = 0L;
        int rank = square / 8;
        int file = square % 8;

        // Note: this doesn't care about piece colour; it only checks which squares can be *seen*, not which ones are worth trying to capture.
        // So the 'break;' happens *after* adding the square to the attacks, even though it might be illegal to actually make that attack.

        // Again, up, down, right, left, in that order.
        // Up.
        for (int r = rank + 1; r <= 7; r++) {
            int sq = r * 8 + file;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        // Down.
        for (int r = rank - 1; r >= 0; r--) {
            int sq = r * 8 + file;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        // Right.
        for (int f = file + 1; f <= 7; f++) {
            int sq = rank * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        // Left.
        for (int f = file - 1; f >= 0; f--) {
            int sq = rank * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        return attacks;
    }

    public static long[][] generateBishopAttacks(long[][] blockerBoards) {
        long[][] attacks = new long[64][];
        for (int i = 0; i < 64; i++) {
            attacks[i] = new long[blockerBoards[i].length];
            for (int j = 0; j < blockerBoards[i].length; j++) {
                attacks[i][j] = getBishopAttack(i, blockerBoards[i][j]);
            }
        }
        return attacks;
    }

    private static long getBishopAttack(int square, long occupancy) {
        long attacks = 0L;
        int rank = square / 8;
        int file = square % 8;

        // Upper right, upper left, lower right, lower left, in that order.
        for (int r = rank + 1, f = file + 1; r <= 7 && f <= 7; r++, f++) {
            int sq = r * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        for (int r = rank + 1, f = file - 1; r <= 7 && f >= 0; r++, f--) {
            int sq = r * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        for (int r = rank - 1, f = file + 1; r >= 0 && f <= 7; r--, f++) {
            int sq = r * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        for (int r = rank - 1, f = file - 1; r >= 0 && f >= 0; r--, f--) {
            int sq = r * 8 + f;
            attacks |= (1L << sq);
            if ((occupancy & (1L << sq)) != 0) break;
        }

        return attacks;
    }

    public static int[] getShifts(long[] masks){
        int[] shifts = new int[64];
        for (int i = 0; i < 64; i++) {
            shifts[i] = 64 - Long.bitCount(masks[i]);
        }
        return shifts;
    }

    private static long generateSparseMagic(Random random) {
        return random.nextLong() & random.nextLong() & random.nextLong();
    }

    public static long[] findMagicNumbers(long[] masks, long[][] blockers, long[][] attacks) {
        long[] magicNumbers = new long[64];
        for (int square = 0; square < 64; square++) {
            long mask = masks[square];
            long[] blockerSet = blockers[square];
            long[] attackSet = attacks[square];

            magicNumbers[square] = findMagicNumber(square, mask, blockerSet, attackSet);
            if (magicNumbers[square] != 0L) {
                // Debugging statement; can be removed later.
                System.out.println("Found magic number for square " + square + ": " + Long.toHexString(magicNumbers[square]));
                System.out.println((int)((blockerSet[11] & masks[square]) * magicNumbers[square] >>> (64 - Long.bitCount(masks[square])))); // Debugging statement; can be removed later.
            }
        }
        return magicNumbers;

    }

    public static long findMagicNumber(int square, long mask, long[] blockerSet, long[] attacks) {
        int numBits = Long.bitCount(mask);
        int tableSize = 1 << numBits;
        int shift = 64 - numBits;

        Random random = new Random(square); // We set the square as the seed to get consistent results.
        // Then we just try a million times to find a magic number that works.
        for (int attempt = 0; attempt < 1500000; attempt++) {
            long magicCandidate = generateSparseMagic(random);
            if (Long.bitCount((magicCandidate * mask) & 0xFF00000000000000L) < 6) continue;

            long[] table = new long[tableSize];
            boolean failed = false;

            Arrays.fill(table, -1L);

            for (int i = 0; i < blockerSet.length; i++) {
                long occupancy = blockerSet[i];
                int index = (int) (((occupancy & mask) * magicCandidate) >>> shift);

                if (table[index] == -1L) {
                    table[index] = attacks[i];
                } else if (table[index] != attacks[i]) {
                    failed = true;
                    break;
                }
            }

            if (!failed) {
                return magicCandidate;
            }
        }

        System.out.println("FAILED TO FIND MAGIC NUMBER FOR SQUARE " + square);
        System.out.println("THIS SHOULD NEVER BE POSSIBLE, BUT IT HAPPENED.");
        System.out.println("YOU, SONNY BOY, HAVE HAD IT.");
        return 0L; // Return 0 if we fail to find a magic number after a million attempts.
        // It should never happen, though; either the seeded random generator always finds the number or it never does, and as far as I can tell, it does.
    }

    public static void printBitboard(long bitboard) {
        for (int rank = 7; rank >= 0; rank--) {
            for (int file = 0; file < 8; file++) {
                int square = rank * 8 + file;
                System.out.print(((bitboard >> square) & 1L) == 0 ? "0" : "1");
            }
            System.out.println();
        }
        System.out.println();
    }
}
