package Board;

import Pieces.*;
import java.util.Arrays;
import static BitboardMoveGen.LookupTableGeneration.*;

public class BitboardBoard {

    // If you're reading this, I'm sorry. This logic really needs to be split up, but alas, time doesn't permit that right now.

    // The board is represented as a 64-bit integer, where each bit represents a square on the board.
    // The LSB is A1, so the whole board is mirrored along the vertical axis.
    // That is to say that if we print the board as a binary string, H8 is the first bit printed, and A1 is the last bit printed.
    // Hence the printBitboard() method for when we need to visualise a board.

    // I think the way to do this is to have the board stored here be the 'canon' version that represents the actual game state.
    // Then we can have the temporary boards be local variables so they can be stored in the stack for speed, and static versions of the methods below can be used to access the lookup tables.

    long[] board = new long[16];

    // Board[0] is all pieces, Board[1] is white pieces, Board[2] is black pieces.
    // Board[3] to Board[8] are the white pieces in the order pawns, knights, bishops, rooks, queens, kings.
    // Board[9] to Board[14] are the black pieces, same order.
    // Board[15] stores miscellaneous data: castling rights, en passant square, player turn, move number, etc.

    // For generating all of these lookup tables, we just call the methods in LookupTableGeneration.java.
    // This code *would* have had a bunch of helpful comments, but I had to refactor everything twice, so we're going to need to talk Harrison Ford into doing another Indy sequel to find them.

    public static final long[] kingLookupTable = generateKingLookupTable();
    public static final long[] knightLookupTable = generateKnightLookupTable();
    public static final long[] bishopMasks = generateBishopMasks();
    public static long[][] bishopLookupTable = new long[64][];
    public static long[] bishopMagicNumbers = new long[64];
    public static int[] bishopShifts = new int[64];
    public static final long[] rookMasks = generateRookMasks();
    public static long[][] rookLookupTable = new long[64][];
    public static long[] rookMagicNumbers = new long[64];
    public static int[] rookShifts = new int[64];


    public BitboardBoard() {
        initialiseBoard();
    }

    // I'm not sure if this should just be part of the constructor; will we ever need to create multiple instances of this class?
    public void generateLookupTables() {
        long[][] bishopBlockers = enumerateAllBlockerBitboards(bishopMasks);
        long[][] rookBlockers = enumerateAllBlockerBitboards(rookMasks);

        bishopShifts = getShifts(bishopMasks);
        rookShifts = getShifts(rookMasks);

        long[][] bishopAttacks = generateBishopAttacks(bishopBlockers);
        long[][] rookAttacks = generateRookAttacks(rookBlockers);

        for (int square = 0; square < 64; square++) {
            bishopMagicNumbers[square] = findMagicNumber(square, bishopMasks[square], bishopBlockers[square], bishopAttacks[square]);
            rookMagicNumbers[square] = findMagicNumber(square, rookMasks[square], rookBlockers[square], rookAttacks[square]);

            long[] bishopTable = new long[1 << Long.bitCount(bishopMasks[square])];
            long[] rookTable = new long[1 << Long.bitCount(rookMasks[square])];
            Arrays.fill(bishopTable, 0L);
            Arrays.fill(rookTable, 0L);

            for (int i = 0; i < bishopBlockers[square].length; i++) {
                int index = (int) (((bishopBlockers[square][i] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square]));
                bishopTable[index] = bishopAttacks[square][i];
            }
            for (int i = 0; i < rookBlockers[square].length; i++) {
                int index = (int) (((rookBlockers[square][i] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square]));
                rookTable[index] = rookAttacks[square][i];
            }

            bishopLookupTable[square] = bishopTable;
            rookLookupTable[square] = rookTable;
        }
    }
    // Again, remember that the LSB is A1, so the whole board is mirrored along the vertical axis.
    public void initialiseBoard() {
        board[0] = 0xFFFF00000000FFFFL; // All pieces; FFFF = 1111111111111111, or two full rows of pieces.
        board[1] = 0x000000000000FFFFL; // White pieces.
        board[2] = 0xFFFF000000000000L; // Black pieces.
        board[3] = 0x000000000000FF00L; // White pawns; FF00 = 1111111100000000, or a row of pawns on rank 2.
        board[4] = 0x0000000000000042L; // White knights; 42 = 01000010, or two knights on B1 and G1.
        board[5] = 0x0000000000000024L; // White bishops; 24 = 00100100, or two bishops on C1 and F1.
        board[6] = 0x0000000000000081L; // White rooks; 81 = 10000001, or two rooks on A1 and H1.
        board[7] = 0x0000000000000008L; // White queen; 08 = 00001000, or a queen on D1.
        board[8] = 0x0000000000000010L; // White king; 10 = 00010000, or a king on E1.
        board[9] = 0x00FF000000000000L; // Black pawns.
        board[10] = 0x4200000000000000L; // Black knights.
        board[11] = 0x2400000000000000L; // Black bishops.
        board[12] = 0x8100000000000000L; // Black rooks.
        board[13] = 0x0800000000000000L; // Black queen.
        board[14] = 0x1000000000000000L; // Black king.
        board[15] = 0x000000000000001FL; // White to move, full castling rights, no en passant square.
    }

    public long getBoard(int index) {
        return board[index];
    }

    public long[] getFullBoard() {
        return board;
    }

    public void setBoard(int index, long newValue) {
        board[index] = newValue;
    }

    public void setFullBoard(long[] newBoard) {
        System.arraycopy(newBoard, 0, board, 0, board.length);
    }

    // TODO: consider changing this and/or adding a version that takes a FEN string.
    public void manualSetBoard(long[] newBoard) {
        System.arraycopy(newBoard, 0, board, 0, board.length);
    }

    // #########################################################################
    // PAWN MOVES.

    // In all of these methods, we mask the moves to prevent them from going out of bounds.
    // For example, '& 0xFEFEFEFEFEFEFEFEL' prevents the pawn from moving left off the board by removing pawns on the a-file ('FE' = 11111110 in binary, so the leftmost bit is cleared).
    // And now you're saying 'wait, the *left*most bit?' Yes, sir, the *left*most bit, because remember, the LSB is A1, so the whole board is mirrored along the vertical axis.

    // Moves and captures are kept separate so we can easily evaluate captures first in the algorithm.

    // Remember that board[0] is all pieces, board[1] is white pieces, and board[2] is black pieces.
    // Board[3] is white pawns, and board[9] is black pawns.

public static long whitePawnMoves(long[] board) {
    long moves = 0L;
    // Single-square moves.
    moves |= (board[3] << 8) & ~board[0];
    // Two-square moves from the second rank.
    moves |= ((board[3] & 0x000000000000FF00L) << 16) & ~board[0] & (~board[0] << 8);
    return moves;
}

public static long blackPawnMoves(long[] board) {
    long moves = 0L;
    // Single-square moves.
    moves |= (board[9] >> 8) & ~board[0];
    // Two-square moves from the seventh rank.
    moves |= ((board[9] & 0x00FF000000000000L) >> 16) & ~board[0] & (~board[0] >> 8);
    return moves;
}

    public static long whitePawnRightCaptures(long[] board) {
        return (board[3] << 9) & board[2] & 0xFEFEFEFEFEFEFEFEL;
    }

    public static long whitePawnLeftCaptures(long[] board){
        return (board[3] << 7) & board[2] & 0x7F7F7F7F7F7F7F7FL;
    }

    public static long blackPawnRightCaptures(long[] board) {
        return (board[9] >> 9) & board[1] & 0xFEFEFEFEFEFEFEFEL;
    }

    public static long blackPawnLeftCaptures(long[] board) {
        return (board[9] >> 7) & board[1] & 0x7F7F7F7F7F7F7F7FL;
    }

    public static long whitePawnRightEnPassant(long[] board) {
        long epSquare = getEnPassantSquare(board[15]);
        long epMask = 1L << epSquare;

        return (board[3] << 9) & epMask & 0x7F7F7F7F7F7F7F7FL;
    }

    public static long whitePawnLeftEnPassant(long[] board) {
        long epSquare = getEnPassantSquare(board[15]);
        long epMask = 1L << epSquare;

        return (board[3] << 7) & epMask & 0xFEFEFEFEFEFEFEFEL;
    }

    public static long blackPawnRightEnPassant(long[] board) {
        long epSquare = getEnPassantSquare(board[15]);
        long epMask = 1L << epSquare;

        return (board[9] >> 9) & epMask & 0x7F7F7F7F7F7F7F7FL;
    }

    public static long blackPawnLeftEnPassant(long[] board) {
        long epSquare = getEnPassantSquare(board[15]);
        long epMask = 1L << epSquare;

        return (board[9] >> 7) & epMask & 0xFEFEFEFEFEFEFEFEL;
    }

    // #########################################################################
    // KING MOVES.

    // For all non-pawn moves, we generate a lookup table at runtime, taking a few seconds at most.
    // This is *way* more practical than hardcoding because, at least for the moves that might be worth hardcoding,
    // you almost immediately run into the upper limit of Java method sizes of 64KB.
    // Because yeah, Rook attack tables *will* take up thousands of 64-bit integers.

    // TODO: castling.

    // Since there's only ever one king, we can just use the LSB to find the king's position and save a little time with parameter passing.

    public static long whiteKingMoves(long[] board) {
        return kingLookupTable[Long.numberOfTrailingZeros(board[8])] & ~board[0];
    }

    public static long whiteKingCaptures(long[] board) {
        return kingLookupTable[Long.numberOfTrailingZeros(board[8])] & board[2];
    }

    public static long blackKingMoves(long[] board) {
        return kingLookupTable[Long.numberOfTrailingZeros(board[14])] & ~board[0];
    }

    public static long blackKingCaptures(long[] board) {
        return kingLookupTable[Long.numberOfTrailingZeros(board[14])] & board[1];
    }

    // #########################################################################
    // KNIGHT MOVES.

    // As with the king, we also use a lookup table generated at runtime.

    // There can be multiple knights, so we need to pass the knight's position as a parameter.
    // That can just be an integer; no need to worry about trailing zeros here.

    public static long knightMoves(int square, long[] board) {
        return knightLookupTable[square] & ~board[0];
    }

    public static long whiteKnightCaptures(int square, long[] board) {
        return knightLookupTable[square] & board[2];
    }

    public static long blackKnightCaptures(int square, long[] board) {
        return knightLookupTable[square] & board[1];
    }

    // #########################################################################
    // BISHOP MOVES.

    // Hoo, boy, now we're getting spicy.
    // Here's how sliding piece attacks work, more or less:
    // First, we create 'masks' for each square on the board, which show which squares *can* block the piece, i.e. excluding edge squares.
    // Then, we generate every possible combination of blocking pieces.
    // Then, we calculate the moves for each combination of blocking pieces. That's the lookup table we need to index, which we'll do with magic numbers.

    // What's a magic number? Good question. Basically, it's possible to brute-force a number for each square that,
    // when multiplied by the occupied squares in the mask and shifted, becomes a unique index for the attack table.
    // Do I, strictly speaking, *understand* this? Vaguely at best, but I tested it, and it works, so I'm a happy man.

    public static long bishopMoves(int square, long[] board) {
        return bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])] & ~board[0];
    }

    public static long whiteBishopCaptures(int square, long[] board) {
        return bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])] & board[2];
    }

    public static long blackBishopCaptures(int square, long[] board) {
        return bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])] & board[1];
    }

    // #########################################################################
    // ROOK MOVES.

    // Second verse, same as the first.

    public static long rookMoves(int square, long[] board) {
        return rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] & ~board[0];
    }

    public static long whiteRookCaptures(int square, long[] board) {
        return rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] & board[2];
    }

    public static long blackRookCaptures(int square, long[] board) {
        return rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] & board[1];
    }

    // #########################################################################
    // QUEEN MOVES.

    // Thankfully, this one's easy when the lookup tables are already generated.
    // We simply get the rook and bishop moves for the square and combine them with a bitwise 'OR'.

    public static long queenMoves(int square, long[] board) {
        return (rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] & ~board[0]) |
                (bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])] & ~board[0]);
    }

    public static long whiteQueenCaptures(int square, long[] board) {
        return (rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] |
                bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])]) & board[2];
    }

    public static long blackQueenCaptures(int square, long[] board) {
        return (rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] |
                bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])]) & board[1];
    }

    // #########################################################################
    // CHECK CHECKS (PUN INTENDED).

    public static boolean isInCheck(long[] board, boolean white) {
        // First we find the king's position.
        int kingSquare = Long.numberOfTrailingZeros(board[white ? 8 : 14]);

        // Then we check from the king's square to all the squares that can attack it and see if any of them are occupied by an enemy piece.
        // For obvious reasons, we don't need to check the enemy king's moves.

        // We check whether any pawn captures for the opposite colour overlap with the king's position, then whether any non-king piece in the king's position can see a corresponding piece of the opposite colour.
        return ((white ? blackPawnRightCaptures(board) : whitePawnRightCaptures(board)) & board[white ? 8 : 14]) != 0L ||
                ((white ? blackPawnLeftCaptures(board) : whitePawnLeftCaptures(board)) & board[white ? 8 : 14]) != 0L ||
                ((white ? whiteKnightCaptures(kingSquare, board) : blackKnightCaptures(kingSquare, board)) & board[white ? 10 : 4]) != 0L ||
                ((white ? whiteBishopCaptures(kingSquare, board) : blackBishopCaptures(kingSquare, board)) & (board[white ? 11 : 5] | board[white ? 13 : 7])) != 0L ||
                ((white ? whiteRookCaptures(kingSquare, board) : blackRookCaptures(kingSquare, board)) & (board[white ? 12 : 6] | board[white ? 13 : 7])) != 0L;

    }

    // ##########################################################################
    // ENCODING MOVES AND MISC DATA.

    // Making a large number of Move objects is inefficient, but we can encode the data into a 32-bit integer instead.
    // When encoding, remember that the LSB is A1, and the MSB is H8.
    // The pieces are valued as follows:
    // 1 = pawn.
    // 2 = knight.
    // 3 = bishop.
    // 4 = rook.
    // 5 = queen.
    // 6 = king.
    public static int encodeMove(int from, int to, int piece, int captured, int promotion, boolean isEP, boolean isCastle, boolean kingSideCastlingRightsChanged, boolean queenSideCastlingRightsChanged, int enPassantFile) {
        return (from) | (to << 6) | (piece << 12) | (captured << 16) | (promotion << 20) | ((isEP ? 1 : 0) << 24) | ((isCastle ? 1 : 0) << 25) |
                ((enPassantFile != -1 ? 1 : 0) << 26) | ((kingSideCastlingRightsChanged ? 1 : 0) << 27) | ((queenSideCastlingRightsChanged ? 1 : 0) << 28) | (enPassantFile << 29);
    }

    // Apparently these are efficient enough that there's no advantage to hardcoding the logic.
    public static int getFrom(int move)       { return move & 0x3F; }  // Bits 0-5
    public static int getTo(int move)         { return (move >>> 6) & 0x3F; }  // Bits 6-11
    public static int getPiece(int move)      { return (move >>> 12) & 0xF; }  // Bits 12-15
    public static int getCaptured(int move)   { return (move >>> 16) & 0xF; }  // Bits 16-19
    public static int getPromotion(int move)  { return (move >>> 20) & 0xF; }  // Bits 20-23
    public static boolean isEnPassant(int move) { return ((move >>> 24) & 1) != 0; }  // Bit 24
    public static boolean isCastling(int move)  { return ((move >>> 25) & 1) != 0; }  // Bit 25
    public static boolean enPassantSquareCleared(int move) { return ((move >>> 26) & 1) != 0; }// Bit 26
    public static boolean kingsideCastlingRightsChanged(int move) { return ((move >>> 27) & 1) != 0; } // Bit 27
    public static boolean queensideCastlingRightsChanged(int move) { return ((move >>> 28) & 1) != 0; } // Bit 28
    public static int enPassantFile(int move) { return ((move >>> 29) & 0x7); } // Bits 29-31

    // As a terribly clever, stupid, and lazy person, here's what I've done with bits 26-31:
    // Since moves need to be reversible, I need to either do a bunch of work to store each state of the board, *or* I could find a way to store changes to castling rights and en passant squares in six bits.
    // Well, guess what? It turns out that's just barely possible.
    // Bits 27-28 store castling rights changes, and we simply distinguish between KQ and kq based on whose turn it is.
    // Bit 26 stores whether the en passant square is set before the move is made.
    // If it is, bits 29-31 store the en passant file, and we again use the current player to determine which rank it's on.

    // Similarly, we can store a lot of miscellaneous data in board[15] instead of needing separate fields for it.
    // The data is stored as follows:
    // Bit 0: white to move. 1 for white, 0 for black.
    // Bit 1-4: castling rights. 1 for available, 0 for unavailable, in the order WK, WQ, BK, BQ.
    // Bit 5-11: en passant square. 0 for no en passant, 1-64 for the square. Remember to subtract 1 from the square to get the index.
    // TODO: add move number and half-move clock. Assuming we need them for anything (king evaluation?); we've got plenty of space in the long.

    public static long encodeMiscData(boolean whiteToMove, boolean[] castlingRights, int enPassantSquare) {
        long miscData = 0L;
        miscData |= (whiteToMove ? 1L : 0L);
        for (int i = 0; i < 4; i++) {
            miscData |= ((castlingRights[i] ? 1L : 0L) << (i + 1));
        }
        miscData |= ((enPassantSquare & 0x3F) << 5);
        return miscData;
    }

    public static boolean isWhiteToMove(long miscData) {
        return (miscData & 1L) != 0L;
    }

    public static long setWhiteToMove(long miscData, boolean whiteToMove) {
        miscData = (miscData & ~1L) | (whiteToMove ? 1L : 0L);
        return miscData;
    }

    public static boolean[] getCastlingRights(long miscData) {
        boolean[] castlingRights = new boolean[4];
        for (int i = 0; i < 4; i++) {
            castlingRights[i] = ((miscData >> (i + 1)) & 1L) != 0L;
        }
        return castlingRights;
    }

    public static long setCastlingRights(long miscData, boolean[] castlingRights) {
        for (int i = 1; i < 5; i++) {
            miscData = (miscData & ~(1L << (i))) | ((castlingRights[i] ? 1L : 0L) << (i));
        }
        return miscData;
    }

    public static int getEnPassantSquare(long miscData) {
        return (int)((miscData >> 5) & 0x3F) - 1;
    }

    public static long setEnPassantSquare(long miscData, int square) {
        miscData = (miscData & 0xFFFFFFFFFFFFF81FL) | ((square + 1 & 0x3FL) << 5); // 0xFFFFFFFFFFFFF81FL clears the en passant bits.
        return miscData;
    }

    // ##########################################################################
    // VARIOUS METHODS FOR MAKING AND FINDING MOVES.

    // Returns the type of piece at the given square.
    public static int getPieceType(int square, long[] board, boolean white) {
        long sqMask = 1L << square;

        if (white) {
            if ((board[3] & sqMask) != 0L) return 1; // Pawn
            if ((board[4] & sqMask) != 0L) return 2; // Knight
            if ((board[5] & sqMask) != 0L) return 3; // Bishop
            if ((board[6] & sqMask) != 0L) return 4; // Rook
            if ((board[7] & sqMask) != 0L) return 5; // Queen
            if ((board[8] & sqMask) != 0L) return 6; // King
        } else {
            if ((board[9] & sqMask) != 0L) return 1; // Pawn
            if ((board[10] & sqMask) != 0L) return 2; // Knight
            if ((board[11] & sqMask) != 0L) return 3; // Bishop
            if ((board[12] & sqMask) != 0L) return 4; // Rook
            if ((board[13] & sqMask) != 0L) return 5; // Queen
            if ((board[14] & sqMask) != 0L) return 6; // King
        }

        return 0;
    }

    // For checking castling rights.
    public static long getAllAttacks(long[] board, boolean white) {
        long attacks = 0L;

        attacks |= white ? whiteKingCaptures(board) : blackKingCaptures(board);
        attacks |= white ? whitePawnLeftCaptures(board) : blackPawnLeftCaptures(board);
        attacks |= white ? whitePawnRightCaptures(board) : blackPawnRightCaptures(board);

        long knights = white ? board[4] : board[10];
        for (int i = 0; i < Long.bitCount(knights); i++) {
            int square = Long.numberOfTrailingZeros(knights);
            attacks |= white ? whiteKnightCaptures(square, board) : blackKnightCaptures(square, board);
            knights &= knights - 1;
        }
        long bishops = white ? board[5] : board[11];
        for (int i = 0; i < Long.bitCount(bishops); i++) {
            int square = Long.numberOfTrailingZeros(bishops);
            attacks |= white ? whiteBishopCaptures(square, board) : blackBishopCaptures(square, board);
            bishops &= bishops - 1;
        }
        long rooks = white ? board[6] : board[12];
        for (int i = 0; i < Long.bitCount(rooks); i++) {
            int square = Long.numberOfTrailingZeros(rooks);
            attacks |= white ? whiteRookCaptures(square, board) : blackRookCaptures(square, board);
            rooks &= rooks - 1;
        }
        long queens = white ? board[7] : board[13];
        for (int i = 0; i < Long.bitCount(queens); i++) {
            int square = Long.numberOfTrailingZeros(queens);
            attacks |= white ? whiteQueenCaptures(square, board) : blackQueenCaptures(square, board);
            queens &= queens - 1;
        }

        return attacks;
    }

    public static int[] getAllMoves(long[] board, boolean white) {
        // Setting a large size for the array so it won't run out of space.
        int[] moves = new int[256];
        int moveCount = 0;
        long enemyAttacks = getAllAttacks(board, !white);
        boolean[] castlingRights = getCastlingRights(board[15]);
        int enPassantFile = getEnPassantSquare(board[15]) % 8; // Finds the file of the en passant square, which we can then encode into the moves.

        // This method doesn't have any logic for determining which moves are best, except that captures are always at the start of the array and therefore examined first.

        // Pawn captures.
        long pawnRightCaptures = white ? whitePawnRightCaptures(board) : blackPawnRightCaptures(board);
        long pawnLeftCaptures = white ? whitePawnLeftCaptures(board) : blackPawnLeftCaptures(board);
        while (pawnRightCaptures != 0L) {
            int targetSquare = Long.numberOfTrailingZeros(pawnRightCaptures);
            int originSquare = white ? targetSquare - 9 : targetSquare + 9;
            if (white ? targetSquare < 55 : targetSquare > 8) {
                moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, getPieceType(targetSquare, board, white), 0, false, false, false, false, enPassantFile);
            } else {
                for (int j = 2; j <= 5; j++) { // Promotion moves.
                    moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, getPieceType(targetSquare, board, white), j, false, false, false, false, enPassantFile);
                }
            }
            pawnRightCaptures &= pawnRightCaptures - 1;
        }

        while (pawnLeftCaptures != 0L) {
            int targetSquare = Long.numberOfTrailingZeros(pawnLeftCaptures);
            int originSquare = white ? targetSquare - 7 : targetSquare + 7;
            if (white ? targetSquare < 55 : targetSquare > 8) {
                moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, getPieceType(targetSquare, board, white), 0, false, false, false, false, enPassantFile);
            } else {
                for (int j = 2; j <= 5; j++) { // Promotion moves.
                    moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, getPieceType(targetSquare, board, white), j, false, false, false, false, enPassantFile);
                }
            }
            pawnLeftCaptures &= pawnLeftCaptures - 1;
        }

        // En passant captures.
        // It's impossible to en passant to the 1st or 8th rank, so we don't need to check for promotions.
        // There can also be at most one en passant square, so no need to loop through the pawns.
        // And naturally, the captured piece is always a pawn.
        if (getEnPassantSquare(board[15]) != -1) {
            long epRightCaptures = white ? whitePawnRightEnPassant(board) : blackPawnRightEnPassant(board);
            long epLeftCaptures = white ? whitePawnLeftEnPassant(board) : blackPawnLeftEnPassant(board);

            if (epRightCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(epRightCaptures);
                int originSquare = white ? targetSquare - 9 : targetSquare + 9;
                moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, 1, 0, true, false, false, false, enPassantFile);
            }

            if (epLeftCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(epLeftCaptures);
                int originSquare = white ? targetSquare - 7 : targetSquare + 7;
                moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, 1, 0, true, false, false, false, enPassantFile);
            }
        }

        // Knight captures.
        long knights = white ? board[4] : board[10];
        while (knights != 0L) {
            int square = Long.numberOfTrailingZeros(knights);
            long knightCaptures = white ? whiteKnightCaptures(square, board) : blackKnightCaptures(square, board);
            while (knightCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(knightCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 2, getPieceType(targetSquare, board, white), 0, false, false, false, false, enPassantFile);
                knightCaptures &= knightCaptures - 1;
            }
            knights &= knights - 1;
        }

        // Bishop captures.
        long bishops = white ? board[5] : board[11];
        while (bishops != 0L) {
            int square = Long.numberOfTrailingZeros(bishops);
            long bishopCaptures = white ? whiteBishopCaptures(square, board) : blackBishopCaptures(square, board);
            while (bishopCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(bishopCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 3, getPieceType(targetSquare, board, white), 0, false, false, false, false, enPassantFile);
                bishopCaptures &= bishopCaptures - 1;
            }
            bishops &= bishops - 1;
        }

        // Rook captures.
        long rooks = white ? board[6] : board[12];
        while (rooks != 0L) {
            int square = Long.numberOfTrailingZeros(rooks);
            long rookCaptures = white ? whiteRookCaptures(square, board) : blackRookCaptures(square, board);
            while (rookCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(rookCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 4, getPieceType(targetSquare, board, white), 0, false, false, square == (white ? 7 : 63) && castlingRights[(white ? 0 : 2)], square == (white ? 0 : 56) && castlingRights[(white ? 1 : 3)], enPassantFile);
                rookCaptures &= rookCaptures - 1;
            }
            rooks &= rooks - 1;
        }

        // Queen captures.
        long queens = white ? board[7] : board[13];
        while (queens != 0L) {
            int square = Long.numberOfTrailingZeros(queens);
            long queenCaptures = white ? whiteQueenCaptures(square, board) : blackQueenCaptures(square, board);
            while (queenCaptures != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(queenCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 5, getPieceType(targetSquare, board, white), 0, false, false, false, false, enPassantFile);
                queenCaptures &= queenCaptures - 1;
            }
            queens &= queens - 1;
        }

        // King captures.
        int kingSquare = Long.numberOfTrailingZeros(white ? board[8] : board[14]);
        long kingCaptures = white ? whiteKingCaptures(board) : blackKingCaptures(board);
        while (kingCaptures != 0L) {
            int targetSquare = Long.numberOfTrailingZeros(kingCaptures);
            moves[moveCount++] = encodeMove(kingSquare, targetSquare, 6, getPieceType(targetSquare, board, white), 0, false, false, castlingRights[(white ? 0 : 2)], castlingRights[(white ? 1 : 3)], enPassantFile);
            kingCaptures &= kingCaptures - 1;
        }

        // Castling moves.

        // Kingside.
        if (white ? castlingRights[0] : castlingRights[2]) {
            boolean pathClear = ((board[0] & (white ? (1L << 5 | 1L << 6) : (1L << 61 | 1L << 62))) == 0L);
            boolean safeSquares = ((enemyAttacks & (white ? (1L << 4 | 1L << 5 | 1L << 6) : (1L << 60 | 1L << 61 | 1L << 62))) == 0L);

            if (pathClear && safeSquares) {
                moves[moveCount++] = encodeMove(white ? 4 : 60, white ? 6 : 62, 6, 0, 0, false, true, true, castlingRights[(white ? 1 : 3)], enPassantFile);
            }
        }

        // Queenside.
        if (white ? castlingRights[1] : castlingRights[3]) {
            boolean pathClear = ((board[0] & (white ? (1L << 1 | 1L << 2 | 1L << 3) : (1L << 57 | 1L << 58 | 1L << 59))) == 0L);
            boolean safeSquares = ((enemyAttacks & (white ? (1L << 2 | 1L << 3 | 1L << 4) : (1L << 58 | 1L << 59 | 1L << 60))) == 0L);

            if (pathClear && safeSquares) {
                moves[moveCount++] = encodeMove(white ? 4 : 60, white ? 2 : 62, 6, 0, 0, false, true, castlingRights[(white ? 0 : 2)], true, enPassantFile);
            }
        }

        // Pawn moves.
        long pawnMoves = white ? whitePawnMoves(board) : blackPawnMoves(board);
        while (pawnMoves != 0L) {
            int targetSquare = Long.numberOfTrailingZeros(pawnMoves);
            int originSquare = white ? targetSquare - 8 : targetSquare + 8;
            if (white ? targetSquare < 55 : targetSquare > 8) {
                moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, 0, 0, false, false, false, false, enPassantFile);
            } else {
                for (int j = 2; j <= 5; j++) { // Promotion moves.
                    moves[moveCount++] = encodeMove(originSquare, targetSquare, 1, 0, j, false, false, false, false, enPassantFile);
                }
            }
            pawnMoves &= pawnMoves - 1;
        }

        // Knight moves.
        knights = white ? board[4] : board[10];
        while (knights != 0L) {
            int square = Long.numberOfTrailingZeros(knights);
            long knightMoves = knightMoves(square, board);
            while (knightMoves != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(knightMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 2, 0, 0, false, false, false, false, enPassantFile);
                knightMoves &= knightMoves - 1;
            }
            knights &= knights - 1;
        }

        // Bishop moves.
        bishops = white ? board[5] : board[11];
        while (bishops != 0L) {
            int square = Long.numberOfTrailingZeros(bishops);
            long bishopMoves = bishopMoves(square, board);
            while (bishopMoves != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(bishopMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 3, 0, 0, false, false, false, false, enPassantFile);
                bishopMoves &= bishopMoves - 1;
            }
            bishops &= bishops - 1;
        }

        // Rook moves.
        rooks = white ? board[6] : board[12];
        while (rooks != 0L) {
            int square = Long.numberOfTrailingZeros(rooks);
            long rookMoves = rookMoves(square, board);
            while (rookMoves != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(rookMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 4, 0, 0, false, false, square == (white ? 7 : 63) && castlingRights[(white ? 0 : 2)], square == (white ? 0 : 56) && castlingRights[(white ? 1 : 3)], enPassantFile);
                rookMoves &= rookMoves - 1;
            }
            rooks &= rooks - 1;
        }

        // Queen moves.
        queens = white ? board[7] : board[13];
        while (queens != 0L) {
            int square = Long.numberOfTrailingZeros(queens);
            long queenMoves = queenMoves(square, board);
            while (queenMoves != 0L) {
                int targetSquare = Long.numberOfTrailingZeros(queenMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 5, 0, 0, false, false, false, false, enPassantFile);
                queenMoves &= queenMoves - 1;
            }
            queens &= queens - 1;
        }

        // King moves.
        kingSquare = Long.numberOfTrailingZeros(white ? board[8] : board[14]);
        long kingMoves = white ? whiteKingMoves(board) : blackKingMoves(board);
        while (kingMoves != 0L) {
            int targetSquare = Long.numberOfTrailingZeros(kingMoves);
            moves[moveCount++] = encodeMove(kingSquare, targetSquare, 6, 0, 0, false, false, castlingRights[(white ? 0 : 2)], castlingRights[(white ? 1 : 3)], enPassantFile);
            kingMoves &= kingMoves - 1;
        }

        return Arrays.copyOf(moves, moveCount);
    }

    // ###########################################################################
    // METHODS FOR MAKING MOVES.

    // Important to keep in mind: XOR (^) toggles bits, moving the piece, while AND (&) clears the bits, removing the piece.
    // The getPiece() method returns 1 for pawns, 2 for knights, 3 for bishops, 4 for rooks, 5 for queens, and 6 for kings.
    // We need to add 2 to the piece type to get the index of the board for white pieces (3-8), and 8 to get the index for black pieces (9-14).

    // TODO: probably add a method to update the canonical board if we aren't going to stick with the 2D array for that.
    // I guess I could also just use the return value to update the board, so that's probably redundant.

    // Fun fact: I originally made makeMove() and undoMove() methods, but then I realised that XOR is reversible, so it was just two identical methods.
    // I know that's not *surprising*, but I just blew my own mind.
    public static long[] makeOrUndoMove(long[] board, int move) {
        boolean white = isWhiteToMove(board[15]);
        if (white) {
            board[1] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the white pieces.
            board[getPiece(move)+2] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the piece type.
            if (getCaptured(move) != 0) {
                if (isEnPassant(move)) {
                    board[0] ^= (1L << getFrom(move)) | (1L << getTo(move) | 1L << getEnPassantSquare(board[15])); // Update the 'all pieces' board.
                    board[2] ^= (1L << getEnPassantSquare(board[15])); // Update the en passant square on the 'black pieces' board.
                    board[9] ^= (1L << getEnPassantSquare(board[15])); // Update the en passant square on the 'black pawns' board.
                } else {
                    board[0] ^= (1L << getFrom(move)); // Update the 'from' square on the 'all pieces' board.
                    board[2] ^= (1L << getTo(move)); // Update the 'to' square on the 'black pieces' board.
                    board[getCaptured(move)+8] ^= (1L << getTo(move)); // Update the captured piece on the black piece board.
                }
            } else {
                board[0] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the 'all pieces' board.
            }
            if (isCastling(move)) { // If the move is a castle, also update the rook's old and new positions.
                if (getTo(move) == 6) { // Kingside.
                    board[0] ^= (1L << 5) | (1L << 7); // Update the 'all pieces' board.
                    board[1] ^= (1L << 5 | 1L << 7); // Update the 'white pieces' board.
                } else { // Queenside.
                    board[0] ^= (1L | 1L << 3); // Update the 'all pieces' board.
                    board[1] ^= (1L | 1L << 3); // Update the 'white pieces' board.
                }
            }
            if (getPromotion(move) != 0) { // If the move is a promotion, update the piece type.
                board[getPromotion(move)+2] ^= (1L << getTo(move)); // Update the promoted piece on the white piece board.
                board[3] ^= (1L << getTo(move)); // Update the pawn on the 'white pawns' board.
            }
        } else {
            board[2] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the black pieces.
            board[getPiece(move)+8] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the piece type.
            if (getCaptured(move) != 0) {
                if (isEnPassant(move)) {
                    board[0] ^= (1L << getFrom(move)) | (1L << getTo(move) | 1L << getEnPassantSquare(board[15])); // Update the 'all pieces' board.
                    board[1] ^= (1L << getEnPassantSquare(board[15])); // Update the en passant square on the 'white pieces' board.
                    board[3] ^= (1L << getEnPassantSquare(board[15])); // Update the en passant on the 'white pawns' board.
                } else {
                    board[0] ^= (1L << getFrom(move)); // Update the 'from' square on the 'all pieces' board.
                    board[1] ^= (1L << getTo(move)); // Update the 'to' square on the 'white pieces' board.
                    board[getCaptured(move)+2] ^= (1L << getTo(move)); // Update the captured piece on the white piece board.
                }
            } else {
                board[0] ^= (1L << getFrom(move)) | (1L << getTo(move)); // Update the 'all pieces' board.
            }
            if (isCastling(move)) { // If the move is a castle, also update the rook's old and new positions.
                if (getTo(move) == 62) { // Kingside.
                    board[0] ^= (1L << 61) | (1L << 63); // Update the 'all pieces' board.
                    board[2] ^= (1L << 61 | 1L << 63); // Update the 'black pieces' board.
                } else { // Queenside.
                    board[0] ^= (1L << 56) | (1L << 59); // Update the 'all pieces' board.
                    board[2] ^= (1L << 56 | 1L << 59); // Update the 'black pieces' board.
                }
            }
            if (getPromotion(move) != 0) { // If the move is a promotion, update the piece type.
                board[getPromotion(move)+8] ^= (1L << getTo(move)); // Update the promoted piece on the black piece board.
                board[9] ^= (1L << getTo(move)); // Update the pawn on the 'black pawns' board.
            }
        }

        // Misc operations.

        if (enPassantSquareCleared(move)) { // If the move clears the en passant square...
            if (getPiece(move) == 1 && getFrom(move) / 8 == (white ? 1 : 6) && getTo(move) / 8 == (white ? 3 : 4)) {
                // If the move is a double pawn push and is being undone, restore the en passant square, otherwise set it.
                board[15] = (board[(white ? 3 : 9)] & (1L << getFrom(move))) != 0 ? setEnPassantSquare(board[15], enPassantFile(move) << (white ? 40 : 16)) : setEnPassantSquare(board[15], getFrom(move) + (white ? 8 : -8));
            } else {
                // Clear the en passant square if already set, otherwise restore it.
                board[15] = getEnPassantSquare(board[15]) != -1 ? setEnPassantSquare(board[15], -1) : setEnPassantSquare(board[15], enPassantFile(move) << (white ? 40 : 16));
            }
        } else if (getPiece(move) == 1 && getFrom(move) / 8 == (white ? 1 : 6) && getTo(move) / 8 == (white ? 3 : 4)) {
            // If the move is a double pawn push and is being undone, clear the en passant square, otherwise set it.
            board[15] = (board[(white ? 3 : 9)] & (1L << getFrom(move))) != 0 ? setEnPassantSquare(board[15], -1) : setEnPassantSquare(board[15], getFrom(move) + (white ? 8 : -8));
        }

        // For castling rights, we can just XOR the move's castling rights changes with the current castling rights.
        // The move stores kingside changes in bit 27 and queenside changes in bit 28.
        // The castling rights are stored in bits 1-4 of the misc data.

        if (white) {
            board[15] ^= ((move & 0x18000000) >>> 26);
        } else {
            board[15] ^= ((move & 0x18000000) >>> 24);
        }

        board[15] ^= 1L; // Pass the turn to the other player. Probably marginally more efficient than using a method to do it, and very easy to hardcode.

        return board;
    }

    // ############################################################################
    // IO METHODS.

    public static Piece getPieceAtSquare(int square, long[] board) {
        if ((board[0] & (1L << square)) == 0L) return null; // If the square is empty, return null.
        boolean white = ((board[1] & (1L << square)) != 0L); // If the square is occupied by a white piece, set white to true.

        int pieceType = getPieceType(square, board, white);
        int rank = square / 8;
        int file = square % 8;

        Piece p = null;

        switch (pieceType) {
            case 1 -> p = new Pawn(white, rank, file);
            case 2 -> p = new Knight(white, rank, file);
            case 3 -> p = new Bishop(white, rank, file);
            case 4 -> p = new Rook(white, rank, file);
            case 5 -> p = new Queen(white, rank, file);
            case 6 -> p = new King(white, rank, file);
        }

        return p;
    }

    // Do we need a reverse of this?
    // TODO: double-check that this is actually getting the right squares.
    public static Board bitboardToBoard(long[] board) {
        Board b = new Board();
        for (int i = 0; i < 64; i++) {
            int rank = i / 8;
            int file = i % 8;
            Piece p = getPieceAtSquare((i ^ 63), board); // The 2D array counts from the top left, but the bitboard counts from the bottom right, so we XOR by 63 to get the opposite square.
            b.board[rank][file] = p;
        }
        return b;
    }

    // TODO: IO methods: bitboard to board and FEN string to bitboard.

    // TODO: check later.
    public static String bitboardToFENString(long[] board) {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                Piece piece = getPieceAtSquare(rank * 8 + file, board);
                if (piece == null) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    fen.append(piece.getFENChar());
                }
            }
            if (emptyCount > 0) {
                fen.append(emptyCount);
            }
            if (rank > 0) {
                fen.append("/");
            }
        }
        fen.append(" ");
        fen.append(isWhiteToMove(board[15]) ? "w" : "b");
        // TODO: add the rest.
        return fen.toString();
    }

    public static long[] FENStringToBitboard(String FENString) {
        long[] board = new long[16];
        // TODO: implement this.
        return board;
    }

}
