package Board;

import Pieces.*;

public class Board {
    public Piece[][] board;

    public Board() {
        board = new Piece[8][8];
        setupPieces();
    }

    private void setupPieces() {
        // Bønder
        for (int col = 0; col < 8; col++) {
            board[1][col] = new Pawn(false, 1, col);
            board[6][col] = new Pawn(true, 6, col);
        }

        // Sorte officerer
        board[0][0] = new Rook(false, 0, 0);
        board[0][1] = new Knight(false, 0, 1);
        board[0][2] = new Bishop(false, 0, 2);
        board[0][3] = new Queen(false, 0, 3);
        board[0][4] = new King(false, 0, 4);
        board[0][5] = new Bishop(false, 0, 5);
        board[0][6] = new Knight(false, 0, 6);
        board[0][7] = new Rook(false, 0, 7);

        // Hvide officerer
        board[7][0] = new Rook(true, 7, 0);
        board[7][1] = new Knight(true, 7, 1);
        board[7][2] = new Bishop(true, 7, 2);
        board[7][3] = new Queen(true, 7, 3);
        board[7][4] = new King(true, 7, 4);
        board[7][5] = new Bishop(true, 7, 5);
        board[7][6] = new Knight(true, 7, 6);
        board[7][7] = new Rook(true, 7, 7);
    }

    /**
     * Check om den angivne farves konge er i skak.
     */
    public boolean isInCheck(boolean white) {
        // Find kongens position
        int kingRow = -1, kingCol = -1;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p instanceof King && p.isWhite() == white) {
                    kingRow = r;
                    kingCol = c;
                    break;
                }
            }
            if (kingRow != -1) break;
        }
        if (kingRow == -1) {
            // Ingen konge fundet -> ikke check
            return false;
        }
        // Tjek alle modstander-brikker
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p != null && p.isWhite() != white) {
                    if (p.isValidMove(kingRow, kingCol, board)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check om den angivne farve er i skakmat.
     */
    public boolean isCheckmate(boolean white) {
        // Dernæst: kun hvis i skak
        if (!isInCheck(white)) return false;
        // For hver brik af farven
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p != null && p.isWhite() == white) {
                    // Prøv alle mulige destinationer
                    for (int nr = 0; nr < 8; nr++) {
                        for (int nc = 0; nc < 8; nc++) {
                            if (p.isValidMove(nr, nc, board)) {
                                // Simuler træk
                                Piece target = board[nr][nc];
                                board[nr][nc] = p;
                                board[r][c] = null;
                                p.setPosition(nr, nc);
                                boolean stillInCheck = isInCheck(white);
                                // Reverter
                                board[r][c] = p;
                                board[nr][nc] = target;
                                p.setPosition(r, c);
                                if (!stillInCheck) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}
