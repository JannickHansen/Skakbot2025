package Pieces;

import javax.swing.*;

public class Bishop extends Piece {

    public Bishop(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("bishop", isWhite);
    }


    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        if (Math.abs(newRow - row) != Math.abs(newCol - col)) return false;

        int rowStep = Integer.compare(newRow, row);
        int colStep = Integer.compare(newCol, col);
        int r = row + rowStep;
        int c = col + colStep;

        while (r != newRow && c != newCol) {
            if (board[r][c] != null) return false;
            r += rowStep;
            c += colStep;
        }

        return board[newRow][newCol] == null || board[newRow][newCol].isWhite() != this.isWhite();
    }
}
