package Pieces;

import javax.swing.*;

public class Queen extends Piece {

    public Queen(boolean isWhite, int row, int col) {
        super(isWhite, row, col);
    }

    @Override
    public ImageIcon getIcon() {
        return Util.PieceImageLoader.getPieceIcon("queen", isWhite);
    }


    @Override
    public boolean isValidMove(int newRow, int newCol, Piece[][] board) {
        Bishop tempBishop = new Bishop(isWhite, row, col);
        Rook tempRook = new Rook(isWhite, row, col);
        return tempBishop.isValidMove(newRow, newCol, board) || tempRook.isValidMove(newRow, newCol, board);
    }
}