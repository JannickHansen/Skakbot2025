package Pieces;

import javax.swing.*;

public abstract class Piece {
    protected boolean isWhite;
    protected int row;
    protected int col;

    public Piece(boolean isWhite, int row, int col) {
        this.isWhite = isWhite;
        this.row = row;
        this.col = col;
    }

    public abstract ImageIcon getIcon();
    public abstract boolean isValidMove(int newRow, int newCol, Piece[][] board);

    /**
     * Opdaterer kun koordinater – bruges til både simulation og rigtige træk.
     */
    public void setPosition(int newRow, int newCol) {
        this.row = newRow;
        this.col = newCol;
    }

    /**
     * Rykker brikken som et “rigtigt” træk – sætter moved-flag i underklasser.
     */
    public void move(int newRow, int newCol) {
        setPosition(newRow, newCol);
    }

    public boolean isWhite() { return isWhite; }
    public int getRow()    { return row; }
    public int getCol()    { return col; }
    public String getFENChar() {
        if (isWhite) {
            switch (this.getClass().getSimpleName()) {
                case "Pawn":   return "P";
                case "Knight": return "N";
                case "Bishop": return "B";
                case "Rook":   return "R";
                case "Queen":  return "Q";
                case "King":   return "K";
            }
        } else {
            switch (this.getClass().getSimpleName()) {
                case "Pawn":   return "p";
                case "Knight": return "n";
                case "Bishop": return "b";
                case "Rook":   return "r";
                case "Queen":  return "q";
                case "King":   return "k";
            }
        }
        return null;
    }
}
