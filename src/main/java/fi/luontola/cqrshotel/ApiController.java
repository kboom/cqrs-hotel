// Copyright © 2016-2017 Esko Luontola
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package fi.luontola.cqrshotel;

import fi.luontola.cqrshotel.capacity.CapacityDto;
import fi.luontola.cqrshotel.capacity.CapacityView;
import fi.luontola.cqrshotel.framework.Command;
import fi.luontola.cqrshotel.framework.Commit;
import fi.luontola.cqrshotel.framework.CompositeHandler;
import fi.luontola.cqrshotel.framework.EventStore;
import fi.luontola.cqrshotel.framework.Handler;
import fi.luontola.cqrshotel.framework.ProjectionsUpdater;
import fi.luontola.cqrshotel.framework.Query;
import fi.luontola.cqrshotel.framework.UpdateProjectionsAfterHandling;
import fi.luontola.cqrshotel.framework.consistency.ObservedPosition;
import fi.luontola.cqrshotel.framework.consistency.UpdateObservedPositionAfterCommit;
import fi.luontola.cqrshotel.framework.consistency.WaitForProjectionToUpdate;
import fi.luontola.cqrshotel.pricing.PricingEngine;
import fi.luontola.cqrshotel.reservation.ReservationRepo;
import fi.luontola.cqrshotel.reservation.commands.MakeReservation;
import fi.luontola.cqrshotel.reservation.commands.MakeReservationHandler;
import fi.luontola.cqrshotel.reservation.commands.SearchForAccommodation;
import fi.luontola.cqrshotel.reservation.commands.SearchForAccommodationCommandHandler;
import fi.luontola.cqrshotel.reservation.queries.FindAllReservations;
import fi.luontola.cqrshotel.reservation.queries.FindAllReservationsHandler;
import fi.luontola.cqrshotel.reservation.queries.FindReservationById;
import fi.luontola.cqrshotel.reservation.queries.FindReservationByIdHandler;
import fi.luontola.cqrshotel.reservation.queries.ReservationDto;
import fi.luontola.cqrshotel.reservation.queries.ReservationOffer;
import fi.luontola.cqrshotel.reservation.queries.ReservationsView;
import fi.luontola.cqrshotel.reservation.queries.SearchForAccommodationQueryHandler;
import fi.luontola.cqrshotel.room.RoomRepo;
import fi.luontola.cqrshotel.room.commands.CreateRoom;
import fi.luontola.cqrshotel.room.commands.CreateRoomHandler;
import fi.luontola.cqrshotel.room.queries.RoomDto;
import fi.luontola.cqrshotel.room.queries.RoomsView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
public class ApiController {

    private final Handler<Command, Commit> commandHandler;
    private final Handler<Query, Object> queryHandler;
    private final ProjectionsUpdater projectionsUpdater;

    private final ReservationsView reservationsView;
    private final RoomsView roomsView;
    private final CapacityView capacityView;

    public ApiController(EventStore eventStore, PricingEngine pricing, Clock clock, ObservedPosition observedPosition) {
        ReservationRepo reservationRepo = new ReservationRepo(eventStore);
        RoomRepo roomRepo = new RoomRepo(eventStore);

        projectionsUpdater = new ProjectionsUpdater(
                reservationsView = new ReservationsView(eventStore),
                roomsView = new RoomsView(eventStore),
                capacityView = new CapacityView(eventStore)
        );

        CompositeHandler<Command, Commit> commandHandler = new CompositeHandler<>();
        commandHandler.register(SearchForAccommodation.class, new SearchForAccommodationCommandHandler(reservationRepo, pricing, clock));
        commandHandler.register(MakeReservation.class, new MakeReservationHandler(reservationRepo, clock));
        commandHandler.register(CreateRoom.class, new CreateRoomHandler(roomRepo));
        this.commandHandler = new UpdateObservedPositionAfterCommit(observedPosition,
                new UpdateProjectionsAfterHandling<>(projectionsUpdater, commandHandler));

        CompositeHandler<Query, Object> queryHandler = new CompositeHandler<>();
        queryHandler.register(SearchForAccommodation.class,
                new SearchForAccommodationQueryHandler(eventStore, clock));
        queryHandler.register(FindAllReservations.class,
                new WaitForProjectionToUpdate<>(reservationsView, observedPosition,
                        new FindAllReservationsHandler(reservationsView)));
        queryHandler.register(FindReservationById.class,
                new WaitForProjectionToUpdate<>(reservationsView, observedPosition,
                        new FindReservationByIdHandler(reservationsView)));
        queryHandler.register(SimpleProjectionQuery.class,
                new SimpleProjectionQueryHandler(observedPosition));
        this.queryHandler = queryHandler;
    }

    @PostConstruct
    public void startup() {
        projectionsUpdater.updateAll();
    }

    @PreDestroy
    public void shutdown() {
        projectionsUpdater.shutdown();
    }

    @RequestMapping(path = "/api", method = GET)
    public String home() {
        return "CQRS Hotel API";
    }

    @RequestMapping(path = "/api/search-for-accommodation", method = POST)
    public ReservationOffer searchForAccommodation(@RequestBody SearchForAccommodation command) {
        commandHandler.handle(command);
        // XXX: commented out; relies on the fact that SearchForAccommodationQueryHandler's projection is updated synchronously (projections should be unified to fix this)
        //waitForProjectionsToUpdate(commit.committedPosition);
        return (ReservationOffer) queryHandler.handle(command);
    }

    @RequestMapping(path = "/api/make-reservation", method = POST)
    public Map<Object, Object> makeReservation(@RequestBody MakeReservation command) {
        commandHandler.handle(command);
        return Collections.emptyMap(); // XXX: WriteObservedPositionToResponseHeaders works only for non-void controller methods
    }

    @RequestMapping(path = "/api/reservations", method = GET)
    public List<ReservationDto> reservations() {
        return (List<ReservationDto>) queryHandler.handle(new FindAllReservations());
    }

    @RequestMapping(path = "/api/reservations/{reservationId}", method = GET)
    public ReservationDto reservationById(@PathVariable String reservationId) {
        return (ReservationDto) queryHandler.handle(new FindReservationById(UUID.fromString(reservationId)));
    }

    @RequestMapping(path = "/api/create-room", method = POST)
    public Map<Object, Object> createRoom(@RequestBody CreateRoom command) {
        commandHandler.handle(command);
        return Collections.emptyMap(); // XXX: WriteObservedPositionToResponseHeaders works only for non-void controller methods
    }

    @RequestMapping(path = "/api/rooms", method = GET)
    public List<RoomDto> rooms() {
        return (List<RoomDto>) queryHandler.handle(new SimpleProjectionQuery<>(roomsView, RoomsView::findAll));
    }

    @RequestMapping(path = "/api/capacity/{date}", method = GET)
    public CapacityDto capacityByDate(@PathVariable String date) {
        LocalDate date_ = LocalDate.parse(date);
        return (CapacityDto) queryHandler.handle(new SimpleProjectionQuery<>(capacityView, view -> view.getCapacityByDate(date_)));
    }

    @RequestMapping(path = "/api/capacity/{start}/{end}", method = GET)
    public List<CapacityDto> capacityByDateRange(@PathVariable String start,
                                                 @PathVariable String end) {
        LocalDate start_ = LocalDate.parse(start);
        LocalDate end_ = LocalDate.parse(end);
        return (List<CapacityDto>) queryHandler.handle(new SimpleProjectionQuery<>(capacityView, view -> view.getCapacityByDateRange(start_, end_)));
    }
}
