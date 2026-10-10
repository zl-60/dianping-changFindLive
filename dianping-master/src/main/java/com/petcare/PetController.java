package com.petcare;

import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.petcare.PetData.*;

@RestController
@RequestMapping("/api/pet")
public class PetController {
    private final PetAuth auth;
    private final PetBookingService booking;
    public PetController(PetAuth auth,PetBookingService booking) { this.auth=auth; this.booking=booking; }
    private Map<String,Object> ok(Object data) { return map("success",true,"data",data); }
    private String customer(HttpServletRequest request) { return (String)auth.require(request,"CUSTOMER").get("subject"); }
    private void owner(HttpServletRequest request) { auth.require(request,"OWNER"); }
    @GetMapping("/store") public Object store() { return ok(booking.store()); }
    @GetMapping("/services") public Object services() { return ok(booking.specs(false)); }
    @GetMapping("/availability") public Object availability(@RequestParam long specId,@RequestParam String date) { return ok(booking.availability(specId,date)); }
    @GetMapping("/reviews") public Object reviews() { return ok(booking.reviews()); }
    @PostMapping("/auth/code") public Object code(@RequestBody Map<String,Object> input) { return ok(auth.code(PetAuth.phone(input.get("phone")))); }
    @PostMapping("/auth/customer") public Object login(@RequestBody Map<String,Object> input) {
        return ok(auth.customerLogin(PetAuth.phone(input.get("phone")),text(input,"code",6)));
    }
    @PostMapping("/auth/owner") public Object ownerLogin(@RequestBody Map<String,Object> input,HttpServletRequest request) {
        return ok(auth.ownerLogin(text(input,"username",40),text(input,"password",128),request.getRemoteAddr()));
    }
    @GetMapping("/auth/me") public Object me(HttpServletRequest request) { return ok(auth.require(request,null)); }
    @PostMapping("/auth/logout") public Object logout(HttpServletRequest request) { auth.logout(request); return ok(null); }
    @PostMapping("/orders") public Object create(@RequestBody Map<String,Object> input,HttpServletRequest request) { return ok(booking.create(customer(request),input)); }
    @GetMapping("/orders") public Object orders(HttpServletRequest request) { return ok(booking.orders(customer(request),false)); }
    @GetMapping("/orders/{id}") public Object order(@PathVariable String id,HttpServletRequest request) { return ok(booking.order(customer(request),id,false)); }
    @PostMapping("/orders/{id}/pay") public Object pay(@PathVariable String id,HttpServletRequest request) { return ok(booking.pay(customer(request),id)); }
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,HttpServletRequest request) { return ok(booking.cancel(customer(request),id)); }
    @PostMapping("/orders/{id}/review") public Object review(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request) { booking.review(customer(request),id,input); return ok(null); }
    @GetMapping("/owner/orders") public Object ownerOrders(HttpServletRequest request) { owner(request); return ok(booking.orders(null,true)); }
    @PostMapping("/owner/orders/{id}/{action}") public Object action(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); return ok(booking.action(id,action,input)); }
    @GetMapping("/owner/stats") public Object stats(HttpServletRequest request) { owner(request); return ok(booking.stats()); }
    @GetMapping("/owner/services") public Object ownerServices(HttpServletRequest request) { owner(request); return ok(booking.specs(true)); }
    @PostMapping("/owner/services") public Object addService(@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveSpec(null,input); return ok(null); }
    @PutMapping("/owner/services/{id}") public Object editService(@PathVariable Long id,@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveSpec(id,input); return ok(null); }
    @GetMapping("/owner/staff") public Object staff(HttpServletRequest request) { owner(request); return ok(booking.staff()); }
    @PostMapping("/owner/staff") public Object addStaff(@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveStaff(null,input); return ok(null); }
    @PutMapping("/owner/staff/{id}") public Object editStaff(@PathVariable Long id,@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveStaff(id,input); return ok(null); }
    @GetMapping("/owner/shifts") public Object shifts(HttpServletRequest request) { owner(request); return ok(booking.shifts()); }
    @PostMapping("/owner/shifts") public Object addShift(@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveShift(input,false); return ok(null); }
    @DeleteMapping("/owner/shifts") public Object deleteShift(@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveShift(input,true); return ok(null); }
    @PutMapping("/owner/store") public Object saveStore(@RequestBody Map<String,Object> input,HttpServletRequest request) { owner(request); booking.saveStore(input); return ok(null); }
}
