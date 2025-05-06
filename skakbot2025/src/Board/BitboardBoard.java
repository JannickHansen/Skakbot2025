package Board;

import java.util.Arrays;

import static BitboardMoveGen.LookupTableGeneration.*;

public class BitboardBoard {

    // It's technically not ideal to keep all of this code in one class, but as I understand it, we save a (possibly tiny) amount of time by not having to pass the board around as a parameter.
    // And since an array is faster than an ArrayList, passing the board as a parameter would lead to new local variables being created every time we call a method.

    // The board is represented as a 64-bit integer, where each bit represents a square on the board.
    // The LSB is A1, so the whole board is mirrored along the vertical axis.
    // That is to say that if we print the board as a binary string, H8 is the first bit printed, and A1 is the last bit printed.
    // Hence the printBitboard() method for when we need to visualise a board.

    // I think the way to do this is to have the board stored here be the 'canon' version that represents the actual game state.
    // Then we can have the temporary boards be local variables so they can be stored in the stack for speed, and static versions of the methods below can be used to access the lookup tables.

    long[] board = new long[15];

    // Board[0] is all pieces, Board[1] is white pieces, Board[2] is black pieces.
    // Board[3] to Board[8] are the white pieces in the order pawns, knights, bishops, rooks, queens, kings.
    // Board[9] to Board[14] are the black pieces, same order.

    // TODO: move these to another bitboard.
    boolean whiteCastlingRights = true;
    boolean blackCastlingRights = true;

    // For generating all of these lookup tables, we just call the methods in LookupTableGeneration.java.
    // This code *would* have had a bunch of helpful comments, but I had to refactor everything twice, so we're going to need to talk Harrison Ford into doing another Indy sequel to find them.

    static final long[] kingLookupTable = generateKingLookupTable();
    static final long[] knightLookupTable = generateKnightLookupTable();
    static final long[] bishopMasks = generateBishopMasks();
    static long[][] bishopLookupTable = new long[64][];
    static long[] bishopMagicNumbers = new long[64];
    static int[] bishopShifts = new int[64];
    static final long[] rookMasks = generateRookMasks();
    static long[][] rookLookupTable = new long[64][];
    static long[] rookMagicNumbers = new long[64];
    static int[] rookShifts = new int[64];


    public BitboardBoard() {
        initialiseBoard();
    }

    // I'm not sure if this should just be part of the constructor; will we ever need to create multiple instances of this class?
    public void generateLookupTables() {
        long[][] bishopBlockers = enumerateAllBlockerBitboards(bishopMasks);
        long[][] rookBlockers = enumerateAllBlockerBitboards(rookMasks);

        bishopLookupTable = generateBishopAttacks(bishopBlockers);
        rookLookupTable = generateRookAttacks(rookBlockers);

        bishopShifts = getShifts(bishopMasks);
        rookShifts = getShifts(rookMasks);

        bishopMagicNumbers = findMagicNumbers(bishopMasks, bishopBlockers, bishopLookupTable);
        rookMagicNumbers = findMagicNumbers(rookMasks, rookBlockers, rookLookupTable);
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
        board [10] = 0x4200000000000000L; // Black knights.
        board[11] = 0x2400000000000000L; // Black bishops.
        board[12] = 0x8100000000000000L; // Black rooks.
        board[13] = 0x0800000000000000L; // Black queen.
        board[14] = 0x1000000000000000L; // Black king.
    }

    public long getBoard(int index) {
        return board[index];
    }

    public void setBoard(int index, long newValue) {
        board[index] = newValue;
    }

    public boolean getWhiteCastlingRights() {
        return whiteCastlingRights;
    }

    public void setWhiteCastlingRights(boolean newValue) {
        whiteCastlingRights = newValue;
    }

    public boolean getBlackCastlingRights() {
        return blackCastlingRights;
    }

    public void setBlackCastlingRights(boolean newValue) {
        blackCastlingRights = newValue;
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

    // TODO: add en passant.
    // TODO: probably add separate 'makePawnMove' and 'makePawnCapture' methods, since it's more difficult to isolate the pawns than it is for the other pieces.

    // Remember that board[0] is all pieces, board[1] is white pieces, and board[2] is black pieces.
    // Board[3] is white pawns, and board[9] is black pawns.

    public static long whitePawnMoves(long[] board) {
        long moves = 0L;
        // Single-square moves.
        moves |= (board[3] << 8) & ~board[0];
        // Two-square moves from second rank.
        moves |= (board[3] << 16) & ~board[0] & ((~board[0] << 8) & 0x000000000000FF00L);
        return moves;
    }

    public static long blackPawnMoves(long[] board) {
        long moves = 0L;
        // Single-square moves.
        moves |= (board[9] >> 8) & ~board[0];
        // Two-square moves from seventh rank.
        moves |= (board[9] >> 16) & ~board[0] & ((~board[0] >> 8) & 0x00FF000000000000L);
        return moves;
    }

    public static long whitePawnCaptures(long[] board) {
        long captures = 0L;
        // Capture left.
        captures |= (board[3] << 7) & board[2] & 0xFEFEFEFEFEFEFEFEL;
        // Capture right.
        captures |= (board[3] << 9) & board[2] & 0x7F7F7F7F7F7F7F7FL;
        return captures;
    }

    public static long blackPawnCaptures(long[] board) {
        long captures = 0L;
        // Capture left.
        captures |= (board[9] >> 7) & board[1] & 0xFEFEFEFEFEFEFEFEL;
        // Capture right.
        captures |= (board[9] >> 9) & board[1] & 0x7F7F7F7F7F7F7F7FL;
        return captures;
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
        return bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])];
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
        return rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])];
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
        return rookLookupTable[square][(int)((board[0] & rookMasks[square]) * rookMagicNumbers[square] >>> rookShifts[square])] |
                bishopLookupTable[square][(int)((board[0] & bishopMasks[square]) * bishopMagicNumbers[square] >>> bishopShifts[square])];
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
        return ((white ? blackPawnCaptures(board) : whitePawnCaptures(board)) & board[white ? 8 : 14]) != 0L ||
                ((white ? whiteKnightCaptures(kingSquare, board) : blackKnightCaptures(kingSquare, board)) & board[white ? 10 : 4]) != 0L ||
                ((white ? whiteBishopCaptures(kingSquare, board) : blackBishopCaptures(kingSquare, board)) & (board[white ? 11 : 5] | board[white ? 13 : 7])) != 0L ||
                ((white ? whiteRookCaptures(kingSquare, board) : blackRookCaptures(kingSquare, board)) & (board[white ? 12 : 6] | board[white ? 13 : 7])) != 0L;

    }

    // ##########################################################################
    // ENCODING MOVES.

    // Making a large number of Move objects is inefficient, but we can encode the data into a 32-bit integer instead.
    // When encoding, remember that the LSB is A1, and the MSB is H8.
    // The pieces are valued as follows:
    // 1 = pawn.
    // 2 = knight.
    // 3 = bishop.
    // 4 = rook.
    // 5 = queen.
    // 6 = king.
    public static int encodeMove(int from, int to, int piece, int captured, int promotion, boolean isEP, boolean isCastle) {
        return (from) | (to << 6) | (piece << 12) | (captured << 16) | (promotion << 20) | ((isEP ? 1 : 0) << 24) | ((isCastle ? 1 : 0) << 25);
    }

    // Apparently these are efficient enough that there's no advantage to hardcoding the logic.
    public static int getFrom(int move)       { return move & 0x3F; }  // Bits 0-5
    public static int getTo(int move)         { return (move >>> 6) & 0x3F; }  // Bits 6-11
    public static int getPiece(int move)      { return (move >>> 12) & 0xF; }  // Bits 12-15
    public static int getCaptured(int move)   { return (move >>> 16) & 0xF; }  // Bits 16-19
    public static int getPromotion(int move)  { return (move >>> 20) & 0xF; }  // Bits 20-23
    public static boolean isEnPassant(int move) { return ((move >>> 24) & 1) != 0; }  // Bit 24
    public static boolean isCastling(int move)  { return ((move >>> 25) & 1) != 0; }  // Bit 25

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

    public static int[] getAllMoves(long[] board, boolean white) {
        // Setting a large size for the array so it won't run out of space.
        int[] moves = new int[256];
        int moveCount = 0;

        // This method doesn't have any logic for determining which moves are best, except that captures are always at the start of the array and therefore examined first.

        // Pawn captures.
        long pawnCaptures = white ? whitePawnCaptures(board) : blackPawnCaptures(board);
        for (int i =  Long.bitCount(pawnCaptures); i > 0; i--) {
            int square = Long.numberOfTrailingZeros(pawnCaptures);
            // TODO: add logic.
            pawnCaptures &= pawnCaptures - 1;
        }

        // Knight captures.
        long knights = white ? board[4] : board[10];
        for (int i = 0; i < Long.bitCount(knights); i++) {
            int square = Long.numberOfTrailingZeros(knights);
            long knightCaptures = white ? whiteKnightCaptures(square, board) : blackKnightCaptures(square, board);
            for (int j = Long.bitCount(knightCaptures); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(knightCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 2, getPieceType(targetSquare, board, white), 0, false, false);
                knightCaptures &= knightCaptures - 1;
            }
            knights &= knights - 1;
        }

        // Bishop captures.
        long bishops = white ? board[5] : board[11];
        for (int i = 0; i < Long.bitCount(bishops); i++) {
            int square = Long.numberOfTrailingZeros(bishops);
            long bishopCaptures = white ? whiteBishopCaptures(square, board) : blackBishopCaptures(square, board);
            for (int j = Long.bitCount(bishopCaptures); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(bishopCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 3, getPieceType(targetSquare, board, white), 0, false, false);
                bishopCaptures &= bishopCaptures - 1;
            }
            bishops &= bishops - 1;
        }

        // Rook captures.
        long rooks = white ? board[6] : board[12];
        for (int i = 0; i < Long.bitCount(rooks); i++) {
            int square = Long.numberOfTrailingZeros(rooks);
            long rookCaptures = white ? whiteRookCaptures(square, board) : blackRookCaptures(square, board);
            for (int j = Long.bitCount(rookCaptures); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(rookCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 4, getPieceType(targetSquare, board, white), 0, false, false);
                rookCaptures &= rookCaptures - 1;
            }
            rooks &= rooks - 1;
        }

        // Queen captures.
        long queens = white ? board[7] : board[13];
        for (int i = 0; i < Long.bitCount(queens); i++) {
            int square = Long.numberOfTrailingZeros(queens);
            long queenCaptures = white ? whiteQueenCaptures(square, board) : blackQueenCaptures(square, board);
            for (int j = Long.bitCount(queenCaptures); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(queenCaptures);
                moves[moveCount++] = encodeMove(square, targetSquare, 5, getPieceType(targetSquare, board, white), 0, false, false);
                queenCaptures &= queenCaptures - 1;
            }
            queens &= queens - 1;
        }

        // King captures.
        int kingSquare = Long.numberOfTrailingZeros(white ? board[8] : board[14]);
        long kingCaptures = white ? whiteKingCaptures(board) : blackKingCaptures(board);
        for (int j = Long.bitCount(kingCaptures); j > 0; j--) {
            int targetSquare = Long.numberOfTrailingZeros(kingCaptures);
            moves[moveCount++] = encodeMove(kingSquare, targetSquare, 6, getPieceType(targetSquare, board, white), 0, false, false);
            kingCaptures &= kingCaptures - 1;
        }

        // Pawn moves.
        long pawnMoves = white ? whitePawnMoves(board) : blackPawnMoves(board);
        // TODO: add logic.

        // Knight moves.
        knights = white ? board[4] : board[10];
        for (int i = 0; i < Long.bitCount(knights); i++) {
            int square = Long.numberOfTrailingZeros(knights);
            long knightMoves = knightMoves(square, board);
            for (int j = Long.bitCount(knightMoves); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(knightMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 2, 0, 0, false, false);
                knightMoves &= knightMoves - 1;
            }
            knights &= knights - 1;
        }

        // Bishop moves.
        bishops = white ? board[5] : board[11];
        for (int i = 0; i < Long.bitCount(bishops); i++) {
            int square = Long.numberOfTrailingZeros(bishops);
            long bishopMoves = bishopMoves(square, board);
            for (int j = Long.bitCount(bishopMoves); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(bishopMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 3, 0, 0, false, false);
                bishopMoves &= bishopMoves - 1;
            }
            bishops &= bishops - 1;
        }

        // Rook moves.
        rooks = white ? board[6] : board[12];
        for (int i = 0; i < Long.bitCount(rooks); i++) {
            int square = Long.numberOfTrailingZeros(rooks);
            long rookMoves = rookMoves(square, board);
            for (int j = Long.bitCount(rookMoves); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(rookMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 4, 0, 0, false, false);
                rookMoves &= rookMoves - 1;
            }
            rooks &= rooks - 1;
        }

        // Queen moves.
        queens = white ? board[7] : board[13];
        for (int i = 0; i < Long.bitCount(queens); i++) {
            int square = Long.numberOfTrailingZeros(queens);
            long queenMoves = queenMoves(square, board);
            for (int j = Long.bitCount(queenMoves); j > 0; j--) {
                int targetSquare = Long.numberOfTrailingZeros(queenMoves);
                moves[moveCount++] = encodeMove(square, targetSquare, 5, 0, 0, false, false);
                queenMoves &= queenMoves - 1;
            }
            queens &= queens - 1;
        }

        // King moves.
        kingSquare = Long.numberOfTrailingZeros(white ? board[8] : board[14]);
        long kingMoves = white ? whiteKingMoves(board) : blackKingMoves(board);
        for (int j = Long.bitCount(kingMoves); j > 0; j--) {
            int targetSquare = Long.numberOfTrailingZeros(kingMoves);
            moves[moveCount++] = encodeMove(kingSquare, targetSquare, 6, 0, 0, false, false);
            kingMoves &= kingMoves - 1;
        }

        return Arrays.copyOf(moves, moveCount);
    }

}
