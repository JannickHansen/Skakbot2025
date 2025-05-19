package Evaluation;

import Board.BitboardBoard;

public class SimpleEvaluation {

    PieceSquareTables tables = new PieceSquareTables();

    private static final long[] FILE_MASK = new long[8];
    private static final long[] PASSED_MASK_WHITE = new long[64];
    private static final long[] PASSED_MASK_BLACK = new long[64];

    static {
        // Initialize
        for (int file = 0; file < 8; file++) {
            long mask = 0L;
            for (int rank = 0; rank < 8; rank++) {
                mask |= 1L << (rank * 8 + file);
            }
            FILE_MASK[file] = mask;
        }
        // Passed pawn masks
        for (int sq = 0; sq < 64; sq++) {
            int file = sq % 8;
            int rank = sq / 8;
            long maskW = 0L;
            long maskB = 0L;
            // White passed pawn
            for (int r = rank + 1; r < 8; r++) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) maskW |= 1L << (r * 8 + f);
                }
            }
            // Black passed pawn
            for (int r = rank - 1; r >= 0; r--) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) maskB |= 1L << (r * 8 + f);
                }
            }
            PASSED_MASK_WHITE[sq] = maskW;
            PASSED_MASK_BLACK[sq] = maskB;
        }
    }

    public int simpleEvaluation(long[] board) {
        int evaluation = 0;
        evaluation += materialEvaluation(board);
        evaluation += pieceSquareEvaluation(board);
        evaluation += pawnStructureEvaluation(board);
        evaluation += positionalEvaluation(board);
        return evaluation;
    }

    int materialEvaluation(long[] board) {
        int evaluation = 0;
        int[] pieceValues = {100, 320, 330, 500, 900, 20000}; // In order: pawns, knights, bishops, rooks, queen, and king.
        int[] pieceCount = new int[6]; // Number of each piece type.

        // Count pieces for both players.
        // Using Long.bitCount to count the number of bits set to '1'.
        for (int i = 0; i < 6; i++) {
            pieceCount[i] += Long.bitCount(board[i + 3]); // White pieces.
            pieceCount[i] -= Long.bitCount(board[i + 9]); // Black pieces.
        }

        for (int i = 0; i < 6; i++) {
            evaluation += pieceCount[i] * pieceValues[i];
        }

        // TODO: add a quick check for low material in endgames.
        return evaluation;
    }

    int pieceSquareEvaluation(long[] board) {
        int evaluation = 0;
        // Evaluate white pieces.
        for (int i = 3; i < 9; i++) {
            long pieces = board[i];
            while (pieces != 0) {
                int square = Long.numberOfTrailingZeros(pieces);
                evaluation += tables.getPieceSquareValue(i - 3, square); // Subtract 3 to get the piece type numbers in PieceSquareTables.java.
                pieces &= pieces - 1; // Remove the least significant bit. As I understand it, this operation subtracts 1, then retains the overlap between the new and old numbers.
            }
        }
        // Then evaluate black pieces.
        for (int i = 9; i < 15; i++) {
            long pieces = board[i];
            while (pieces != 0) {
                int square = Long.numberOfTrailingZeros(pieces);
                evaluation -= tables.getPieceSquareValue(i - 3, square);
                pieces &= pieces - 1;
            }
        }
        return evaluation;
    }

    int pawnStructureEvaluation(long[] board) {
        long wp = board[3];
        long bp = board[9];
        int eval = 0;
        // Doubled pawn penalties
        int wd = countDoubledPawnPenalty(wp);
        int bd = countDoubledPawnPenalty(bp);
        eval -= wd;
        eval += bd;
        // Isolated pawn penalties
        int wi = countIsolatedPawnPenalty(wp);
        int bi = countIsolatedPawnPenalty(bp);
        eval -= wi;
        eval += bi;
        // Passed pawn bonuses
        eval += countPassedPawnBonus(wp, bp, PASSED_MASK_WHITE);
        eval -= countPassedPawnBonus(bp, wp, PASSED_MASK_BLACK);
        return eval;
    }

    int countDoubledPawnPenalty(long pawnBB) {
        int penalty = 0;
        for (int file = 0; file < 8; file++) {
            int c = Long.bitCount(pawnBB & FILE_MASK[file]);
            if (c > 1) penalty += 20 * (c - 1);
        }
        return penalty;
    }

    int countIsolatedPawnPenalty(long pawnBB) {
        int penalty = 0;
        for (int sq = 0; sq < 64; sq++) {
            if (((pawnBB >>> sq) & 1) == 0) continue;
            int file = sq % 8;
            long neigh = 0L;
            if (file > 0) neigh |= FILE_MASK[file - 1];
            if (file < 7) neigh |= FILE_MASK[file + 1];
            if ((pawnBB & neigh) == 0) penalty += 15;
        }
        return penalty;
    }

    int countPassedPawnBonus(long ourPawns, long theirPawns, long[] passedMask) {
        int bonus = 0;
        for (int sq = 0; sq < 64; sq++) {
            if (((ourPawns >>> sq) & 1) == 0) continue;
            if ((theirPawns & passedMask[sq]) == 0) {
                int rank = sq / 8;
                bonus += 30 + 5 * rank;
            }
        }
        return bonus;
    }

    int positionalEvaluation(long[] board) {
        // Combined into one so we only need to check the attacks once.
        int eval = 0;
        long[] whiteAttacks = BitboardBoard.getAllAttacks(board, true);
        long[] blackAttacks = BitboardBoard.getAllAttacks(board, false);
        boolean[] castlingRights = BitboardBoard.getCastlingRights(board[15]);


        // White castling.

        if (castlingRights[0]) {
            boolean pathClear = ((board[0] & (1L << 5 | 1L << 6)) == 0L);
            boolean safeSquares = ((blackAttacks[0] & (1L << 4 | 1L << 5 | 1L << 6)) == 0L);
            if (pathClear && safeSquares) {
                eval += 50;
            }
        }

        if (castlingRights[1]) {
            boolean pathClear = ((board[0] & (1L << 1 | 1L << 2 | 1L << 3)) == 0L);
            boolean safeSquares = ((blackAttacks[0] & (1L << 2 | 1L << 3 | 1L << 4)) == 0L);
            if (pathClear && safeSquares) {
                eval += 50;
            }
        }

        // Black castling.

        if (castlingRights[2]) {
            boolean pathClear = ((board[0] & (1L << 61 | 1L << 62)) == 0L);
            boolean safeSquares = ((whiteAttacks[0] & (1L << 60 | 1L << 61 | 1L << 62)) == 0L);
            if (pathClear && safeSquares) {
                eval -= 50;
            }
        }

        if (castlingRights[3]) {
            boolean pathClear = ((board[0] & (1L << 57 | 1L << 58 | 1L << 59)) == 0L);
            boolean safeSquares = ((whiteAttacks[0] & (1L << 58 | 1L << 59 | 1L << 60)) == 0L);
            if (pathClear && safeSquares) {
                eval -= 50;
            }
        }

        // Number of possible moves by piece type. This version is a bit quick and dirty, since it doesn't factor in multiple pieces of the same type being able to attack the same square, but I imagine the effect is negligible.

        int[] multipliers = {1, 3, 4, 3, 2, 1}; // Pawn, knight, bishop, rook, queen, king. Can be tweaked to fine-tune the weights.

        for (int i = 0; i < 6; i++) {
            eval += Long.bitCount(whiteAttacks[i + 1]) * multipliers[i];
            eval -= Long.bitCount(blackAttacks[i + 1]) * multipliers[i];
        }

        return eval;
    }
}