package Pieces;

import javax.swing.*;

public class King extends Piece {

    public King(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("king", isWhite);
    }


    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        int rowDiff = Math.abs(newRow - row);
        int colDiff = Math.abs(newCol - col);
        return rowDiff <= 1 && colDiff <= 1 &&
                (board[newRow][newCol] == null || board[newRow][newCol].isWhite() != this.isWhite());
    }
}
