package Pieces;

import javax.swing.*;

public class Knight extends Piece {

    public Knight(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("knight", isWhite);
    }


    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        int rowDiff = Math.abs(newRow - row);
        int colDiff = Math.abs(newCol - col);
        return (rowDiff == 2 && colDiff == 1 || rowDiff == 1 && colDiff == 2)
                && (board[newRow][newCol] == null || board[newRow][newCol].isWhite() != this.isWhite());
    }
}
