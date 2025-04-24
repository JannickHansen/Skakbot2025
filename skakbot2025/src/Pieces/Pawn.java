package Pieces;

import javax.swing.*;

public class Pawn extends Piece {

    public Pawn(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("pawn", isWhite);
    }


    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        int direction = isWhite ? -1 : 1;
        // To felter frem fra startposition
        if (newCol == col && board[newRow][newCol] == null) {
            if (row == (isWhite ? 6 : 1) && newRow == row + 2*direction) {
                // Tjek at mellemfeltet er tomt
                return board[row + direction][col] == null;
            }
            // Almindeligt ét felt frem
            return newRow == row + direction;
        }
        // Fang diagonalt
        if (Math.abs(newCol - col) == 1 && newRow == row + direction
                && board[newRow][newCol] != null
                && board[newRow][newCol].isWhite() != this.isWhite()) {
            return true;
        }
        return false;
    }

}
