package Pieces;

import javax.swing.*;

public class Rook extends Piece {
    private boolean hasMoved;

    public Rook(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
        this.hasMoved = false;
    }
    public void setHasMoved(boolean moved) {
        this.hasMoved = moved;
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("rook", isWhite);
    }

    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        if (row != newRow && col != newCol) return false;
        int rowStep = Integer.compare(newRow, row);
        int colStep = Integer.compare(newCol, col);
        int r = row + rowStep, c = col + colStep;
        while (r != newRow || c != newCol) {
            if (board[r][c] != null) return false;
            r += rowStep; c += colStep;
        }
        return board[newRow][newCol] == null || board[newRow][newCol].isWhite() != isWhite;
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
