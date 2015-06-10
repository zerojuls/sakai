/**********************************************************************************
 *
 * Copyright (c) 2015 The Sakai Foundation
 *
 * Original developers:
 *
 *   New York University
 *   Payten Giles
 *   Mark Triggs
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.pasystem.impl.banners;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.pasystem.api.Acknowledger;
import org.sakaiproject.pasystem.api.Banner;
import org.sakaiproject.pasystem.api.Banners;
import org.sakaiproject.pasystem.api.PASystemException;
import org.sakaiproject.pasystem.impl.acknowledgements.AcknowledgementStorage;
import org.sakaiproject.pasystem.impl.common.DB;
import org.sakaiproject.pasystem.impl.common.DBAction;
import org.sakaiproject.pasystem.impl.common.DBConnection;
import org.sakaiproject.pasystem.impl.common.DBResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query and store Banner objects in the database.
 */
public class BannerStorage implements Banners, Acknowledger {

    private static final Logger LOG = LoggerFactory.getLogger(BannerStorage.class);

    @Override
    public List<Banner> getAll() {
        return DB.transaction
                ("Find all banners",
                        new DBAction<List<Banner>>() {
                            @Override
                            public List<Banner> call(DBConnection db) throws SQLException {
                                List<Banner> banners = new ArrayList<Banner>();
                                try (DBResults results = db.run("SELECT * from pasystem_banner_alert")
                                        .executeQuery()) {
                                    for (ResultSet result : results) {
                                        banners.add(new Banner(result.getString("uuid"),
                                                result.getString("message"),
                                                result.getString("hosts"),
                                                (result.getInt("active") == 1),
                                                result.getLong("start_time"),
                                                result.getLong("end_time"),
                                                result.getString("banner_type")));
                                    }

                                    return banners;
                                }
                            }
                        }
                );
    }

    @Override
    public Optional<Banner> getForId(final String uuid) {
        return DB.transaction
                ("Find a banner by uuid",
                        new DBAction<Optional<Banner>>() {
                            @Override
                            public Optional<Banner> call(DBConnection db) throws SQLException {
                                try (DBResults results = db.run("SELECT * from pasystem_banner_alert WHERE uuid = ?")
                                        .param(uuid)
                                        .executeQuery()) {
                                    for (ResultSet result : results) {
                                        return Optional.of(new Banner(result.getString("uuid"),
                                                result.getString("message"),
                                                result.getString("hosts"),
                                                (result.getInt("active") == 1),
                                                result.getLong("start_time"),
                                                result.getLong("end_time"),
                                                result.getString("banner_type")));
                                    }

                                    return Optional.empty();
                                }
                            }
                        }
                );
    }

    @Override
    public List<Banner> getRelevantBanners(final String serverId, final String userEid) {
        final String sql = ("SELECT alert.*, dismissed.state as dismissed_state, dismissed.dismiss_time as dismissed_time" +
                " from pasystem_banner_alert alert" +
                " LEFT OUTER JOIN pasystem_banner_dismissed dismissed on dismissed.uuid = alert.uuid" +
                "  AND ((? = '') OR dismissed.user_eid = ?)" +
                " where ACTIVE = 1 AND" +

                // And either hasn't been dismissed yet
                " (dismissed.state is NULL OR" +

                // Or was dismissed temporarily
                "  (dismissed.state = 'temporary'))" +

                " ORDER BY start_time");

        return DB.transaction
                ("Find all active banners for the server: " + serverId,
                        new DBAction<List<Banner>>() {
                            @Override
                            public List<Banner> call(DBConnection db) throws SQLException {
                                List<Banner> banners = new ArrayList<Banner>();
                                try (DBResults results = db.run(sql)
                                        .param((userEid == null) ? "" : userEid.toLowerCase())
                                        .param((userEid == null) ? "" : userEid.toLowerCase())
                                        .executeQuery()) {
                                    for (ResultSet result : results) {
                                        boolean hasBeenDismissed =
                                                (Acknowledger.TEMPORARY.equals(result.getString("dismissed_state")) &&
                                                        (System.currentTimeMillis() - result.getLong("dismissed_time")) < getTemporaryTimeoutMilliseconds());

                                        Banner banner = new Banner(result.getString("uuid"),
                                                result.getString("message"),
                                                result.getString("hosts"),
                                                (result.getInt("active") == 1),
                                                result.getLong("start_time"),
                                                result.getLong("end_time"),
                                                result.getString("banner_type"),
                                                hasBeenDismissed);

                                        if (banner.isActiveForHost(serverId)) {
                                            banners.add(banner);
                                        }
                                    }

                                    Collections.sort(banners);

                                    return banners;
                                }
                            }
                        }
                );
    }

    private int getTemporaryTimeoutMilliseconds() {
        return ServerConfigurationService.getInt("pasystem.banner.temporary-timeout-ms", (24 * 60 * 60 * 1000));
    }

    @Override
    public String createBanner(Banner banner) {
        return DB.transaction("Create an banner",
                new DBAction<String>() {
                    @Override
                    public String call(DBConnection db) throws SQLException {
                        String id = UUID.randomUUID().toString();

                        db.run("INSERT INTO pasystem_banner_alert (uuid, message, hosts, active, start_time, end_time, banner_type) VALUES (?, ?, ?, ?, ?, ?, ?)")
                                .param(id)
                                .param(banner.getMessage())
                                .param(banner.getHosts())
                                .param(Integer.valueOf(banner.isActive() ? 1 : 0))
                                .param(banner.getStartTime())
                                .param(banner.getEndTime())
                                .param(banner.getType())
                                .executeUpdate();

                        db.commit();

                        return id;
                    }
                }
        );
    }

    @Override
    public void updateBanner(Banner banner) {
        DB.transaction("Update banner with uuid " + banner.getUuid(),
                new DBAction<Void>() {
                    @Override
                    public Void call(DBConnection db) throws SQLException {
                        db.run("UPDATE pasystem_banner_alert SET message = ?, hosts = ?, active = ?, start_time = ?, end_time = ?, banner_type = ? WHERE uuid = ?")
                                .param(banner.getMessage())
                                .param(banner.getHosts())
                                .param(Integer.valueOf(banner.isActive() ? 1 : 0))
                                .param(banner.getStartTime())
                                .param(banner.getEndTime())
                                .param(banner.getType())
                                .param(banner.getUuid())
                                .executeUpdate();

                        db.commit();

                        return null;
                    }
                }
        );
    }

    @Override
    public void deleteBanner(String uuid) {
        DB.transaction("Update banner with uuid " + uuid,
                new DBAction<Void>() {
                    @Override
                    public Void call(DBConnection db) throws SQLException {
                        db.run("DELETE FROM pasystem_banner_dismissed WHERE uuid = ?")
                                .param(uuid)
                                .executeUpdate();

                        db.run("DELETE FROM pasystem_banner_alert WHERE uuid = ?")
                                .param(uuid)
                                .executeUpdate();

                        db.commit();

                        return null;
                    }
                }
        );
    }

    @Override
    public void acknowledge(final String uuid, final String userEid) {
        acknowledge(uuid, userEid, calculateAcknowledgementType(uuid));
    }

    @Override
    public void acknowledge(final String uuid, final String userEid, final String acknowledgementType) {
        new AcknowledgementStorage(AcknowledgementStorage.NotificationType.BANNER).acknowledge(uuid, userEid, acknowledgementType);
    }

    private String calculateAcknowledgementType(String uuid) {
        Optional<Banner> banner = getForId(uuid);

        if (banner.isPresent()) {
            return banner.get().calculateAcknowledgementType();
        } else {
            throw new PASystemException("No banner found for uuid: " + uuid);
        }
    }

    @Override
    public void clearTemporaryDismissedForUser(String userEid) {
        new AcknowledgementStorage(AcknowledgementStorage.NotificationType.BANNER).clearTemporaryDismissedForUser(userEid);
    }
}
