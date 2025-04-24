package Pieces;

import javax.swing.*;

public abstract class Piece {
    protected boolean isWhite;
    protected int row, col;
    protected String name;
    public int value;

    public Piece(boolean isWhite, int row, int col) {
        this.isWhite = isWhite;
        this.row = row;
        this.col = col;
    }

    public boolean isWhite() {
        return isWhite;
    }

    public void setPosition(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public abstract boolean isValidMove(int newRow, int newCol, Piece[][] board);
    public abstract ImageIcon getIcon(); // bruges af GUI'en til at vise billedet
}
