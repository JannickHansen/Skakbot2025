package Evaluation;

class PieceSquareTables{
    // Set shorthands for the piece types to make them easier to read and reference.
    public static final int WP = 0; // White pawn.
    public static final int WN = 1; // White knight.
    public static final int WB = 2; // Etc. Etc.
    public static final int WR = 3;
    public static final int WQ = 4;
    public static final int WK = 5;
    public static final int BP = 6;
    public static final int BN = 7;
    public static final int BB = 8;
    public static final int BR = 9;
    public static final int BQ = 10;
    public static final int BK = 11;

    // Piece square tables using values from chessprogramming.org.
    // Rather than having separate tables for each player, the flip() method is used to get the square for the white pieces.
    // Improving pawn evaluation is a high priority.

    // TODO: ChatGPT might've hallucinated about how the bitboard is stored; check if these should be mirrored vertically.

    int[] pawnTable = {
            0,  0,  0,  0,  0,  0,  0,  0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5,  5, 10, 25, 25, 10,  5,  5,
            0,  0,  0, 20, 20,  0,  0,  0,
            5, -5,-10,  0,  0,-10, -5,  5,
            5, 10, 10,-20,-20, 10, 10,  5,
            0,  0,  0,  0,  0,  0,  0,  0
    };

    int[] knightTable = {
            -50,-40,-30,-30,-30,-30,-40,-50,
            -40,-20,  0,  0,  0,  0,-20,-40,
            -30,  0, 10, 15, 15, 10,  0,-30,
            -30,  5, 15, 20, 20, 15,  5,-30,
            -30,  0, 15, 20, 20, 15,  0,-30,
            -30,  5, 10, 15, 15, 10,  5,-30,
            -40,-20,  0,  5,  5,  0,-20,-40,
            -50,-40,-30,-30,-30,-30,-40,-50
    };

    int[] bishopTable = {
            -20,-10,-10,-10,-10,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5, 10, 10,  5,  0,-10,
            -10,  5,  5, 10, 10,  5,  5,-10,
            -10,  0, 10, 10, 10, 10,  0,-10,
            -10, 10, 10, 10, 10, 10, 10,-10,
            -10,  5,  0,  0,  0,  0,  5,-10,
            -20,-10,-10,-10,-10,-10,-10,-20
    };

    int[] rookTable = {
            0,  0,  0,  0,  0,  0,  0,  0,
            5, 10, 10, 10, 10, 10, 10,  5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            0,  0,  0,  5,  5,  0,  0,  0
    };

    // TODO: flip this one vertically to account for the asymmetry.
    int[] queenTable = {
            -20,-10,-10, -5, -5,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5,  5,  5,  5,  0,-10,
            -5,  0,  5,  5,  5,  5,  0, -5,
            0,  0,  5,  5,  5,  5,  0, -5,
            -10,  5,  5,  5,  5,  5,  0,-10,
            -10,  0,  5,  0,  0,  0,  0,-10,
            -20,-10,-10, -5, -5,-10,-10,-20
    };

    int[] kingTableMG = {
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
            20, 20,  0,  0,  0,  0, 20, 20,
            20, 30, 10,  0,  0, 10, 30, 20
    };

    int[] kingTableEG = {
            -50,-40,-30,-20,-20,-30,-40,-50,
            -30,-20,-10,  0,  0,-10,-20,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-30,  0,  0,  0,  0,-30,-30,
            -50,-30,-30,-30,-30,-30,-30,-50
    };

    int flip(int square){
        // Gets the opposite square on the board.
        // This works by flipping the rank bits, but not the file bits.
        // This whole bit manipulation thing is a bit hard to grok, I'll be honest.
        // Also note that because the tables from chessprogramming.org have the 8th rank at the *start* of the array,
        // but the board is indexed starting at a1 (or so I'm told is conventional), flip() needs to be called for white pieces,
        // even though it feels like it should be the other way around.
        return square ^ 56;
    }

    public int getPieceSquareValue(int piece, int square){
        // Gets the value of the piece on the square.
        switch (piece) {
            case WP -> {
                return pawnTable[flip(square)];
            }
            case WN -> {
                return knightTable[flip(square)];
            }
            case WB -> {
                return bishopTable[flip(square)];
            }
            case WR -> {
                return rookTable[flip(square)];
            }
            case WQ -> {
                return queenTable[flip(square)];
            }
            case WK -> {
                return kingTableMG[flip(square)];
            }
            case BP -> {
                return -pawnTable[square];
            }
            case BN -> {
                return -knightTable[square];
            }
            case BB -> {
                return -bishopTable[square];
            }
            case BR -> {
                return -rookTable[square];
            }
            case BQ -> {
                return -queenTable[square];
            }
            case BK -> {
                return -kingTableEG[square];
            }
            default -> throw new IllegalArgumentException("Invalid piece type: " + piece);
        }
    }

}