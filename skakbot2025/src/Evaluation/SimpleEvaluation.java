package Evaluation;

import Pieces.Piece;

public class SimpleEvaluation {

    PieceSquareTables tables = new PieceSquareTables();

    public int simpleEvaluation(long[] board) {
        int evaluation = 0;
        evaluation += materialEvaluation(board);
        evaluation += pieceSquareEvaluation(board);
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

}