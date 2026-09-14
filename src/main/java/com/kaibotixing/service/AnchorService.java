package com.kaibotixing.service;

import com.kaibotixing.dao.AnchorDao;
import com.kaibotixing.model.Anchor;

import java.sql.SQLException;
import java.util.List;

/**
 * 主播业务服务。
 */
public class AnchorService {

    private final AnchorDao anchorDao = new AnchorDao();

    public List<Anchor> findAll() throws SQLException {
        return anchorDao.findAll();
    }

    public List<Anchor> findEnabled() throws SQLException {
        return anchorDao.findEnabled();
    }

    public long add(Anchor anchor) throws SQLException {
        return anchorDao.insert(anchor);
    }

    public void update(Anchor anchor) throws SQLException {
        anchorDao.update(anchor);
    }

    public void delete(long id) throws SQLException {
        anchorDao.delete(id);
    }
}
