package Pieces;

import javax.swing.*;

public class King extends Piece {
    private boolean hasMoved;

    public King(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
        this.hasMoved = false;
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("king", isWhite);
    }

    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        int rowDiff = Math.abs(newRow - row);
        int colDiff = Math.abs(newCol - col);
        return rowDiff <= 1 && colDiff <= 1
                && (board[newRow][newCol] == null || board[newRow][newCol].isWhite() != this.isWhite);
    }

    // Kun opdatering af koordinater til simulationer
    @Override
    public void setPosition(int newRow, int newCol) {
        super.setPosition(newRow, newCol);
    }

    // Rigtigt træk – sæt hasMoved
    @Override
    public void move(int newRow, int newCol) {
        super.setPosition(newRow, newCol);
        hasMoved = true;
    }

    public boolean hasMoved() {
        return hasMoved;
    }
}
